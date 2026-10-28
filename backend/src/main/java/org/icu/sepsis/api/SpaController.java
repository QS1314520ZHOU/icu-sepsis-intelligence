package org.icu.sepsis.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
  @GetMapping({
    "/",
    "/login",
    "/embed",
    "/overview",
    "/patients",
    "/patients/{id}",
    "/alerts",
    "/bundles",
    "/quality",
    "/models"
  })
  public String app() {
    return "forward:/index.html";
  }
}
