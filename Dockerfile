FROM eclipse-temurin:21-jre

WORKDIR /app

COPY target/JonnyBot.jar app.jar

CMD ["java", "-jar", "app.jar"]
