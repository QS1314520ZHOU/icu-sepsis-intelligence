# Development runtime template only; NOT tested in this environment.
# Build release jar with Java15 first. Pin and verify the base image digest before use.
FROM azul/zulu-openjdk:15
WORKDIR /app
COPY release/sepsis-intelligence-0.3.0.jar app.jar
ENV BIND_ADDRESS=0.0.0.0
EXPOSE 8080
USER 10001
ENTRYPOINT ["java","-jar","/app/app.jar"]
