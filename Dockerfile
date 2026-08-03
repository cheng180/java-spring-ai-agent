FROM eclipse-temurin:17-jre
WORKDIR /app
COPY app.jar app.jar
COPY company_inventory.db /app/company_inventory.db
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx256m", "-Xms128m", "-jar", "app.jar"]