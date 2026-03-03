FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/cosh-grid-gateway.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
