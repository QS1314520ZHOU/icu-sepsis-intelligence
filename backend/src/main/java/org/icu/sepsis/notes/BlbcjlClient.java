package org.icu.sepsis.notes;
import java.net.*;import java.io.*;import java.util.*;import com.fasterxml.jackson.databind.ObjectMapper;
/** Bounded POST transport. No redirect, retry, response logging or clinical/model side effect. */
public class BlbcjlClient {
 public static class Failure extends IOException {public final String code;public Failure(String code){super("病程接口读取失败，请按页面状态核对配置或响应");this.code=code;}}
 private final BlbcjlProperties props;
 public BlbcjlClient(BlbcjlProperties props){this.props=props;}
 public byte[] fetch(String blh) throws IOException {
  if(props.endpoint.isBlank()||props.accessKey.isBlank()||props.bqdm==null||blh==null||blh.isBlank())throw new Failure("interface_config_missing");
  HttpURLConnection connection=null;
  try{
   URI uri=URI.create(props.endpoint);if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)throw new IOException();
   connection=(HttpURLConnection)uri.toURL().openConnection();connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setConnectTimeout(Math.max(100,Math.min(30000,props.connectTimeoutMs)));connection.setReadTimeout(Math.max(100,Math.min(60000,props.readTimeoutMs)));connection.setDoOutput(true);
   connection.setRequestProperty("Content-Type","application/json; charset=UTF-8");connection.setRequestProperty("Accept","application/json");
   Map<String,Object> request=new LinkedHashMap<>();request.put("accesskey",props.accessKey);request.put("method","BLBCJL");request.put("data",Map.of("BLH",blh,"BQDM",props.bqdm,"MBKMC",props.mbkmc,"WJJGMC",props.wjjgmc));byte[] payload=new ObjectMapper().writeValueAsBytes(request);connection.setFixedLengthStreamingMode(payload.length);try(var stream=connection.getOutputStream()){stream.write(payload);}
   if(connection.getResponseCode()!=200)throw new Failure("interface_http_error");String type=Objects.toString(connection.getContentType(),"").toLowerCase(Locale.ROOT);String media=type.split(";",2)[0].trim();if(!"application/json".equals(media)&&!media.endsWith("+json"))throw new Failure("interface_content_type");if(type.contains("charset=")&&!type.matches(".*charset=\\s*[\"']?utf-8[\"']?\\s*(;.*)?"))throw new IOException();
   int limit=Math.max(1024,Math.min(20*1024*1024,props.maxResponseBytes));if(connection.getContentLengthLong()>limit)throw new Failure("interface_response_too_large");try(var stream=connection.getInputStream()){byte[] bytes=stream.readNBytes(limit+1);if(bytes.length>limit)throw new Failure("interface_response_too_large");return bytes;}
  }catch(Failure failure){throw failure;}catch(SocketTimeoutException timeout){throw new SocketTimeoutException("病程来源读取超时");}catch(Exception ignored){throw new Failure("interface_network_error");}finally{if(connection!=null)connection.disconnect();}
 }
}
