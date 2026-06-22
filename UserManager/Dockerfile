# Stage 1 — build the JAR
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY UserManager/pom.xml .
COPY UserManager/src src
COPY UserManager/mvnw .
COPY UserManager/.mvn .mvn
RUN chmod +x ./mvnw
RUN ./mvnw clean package -DskipTests

# Stage 2 — run the JAR
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]