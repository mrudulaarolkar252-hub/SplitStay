# Build phase
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY . .
# Change directory into SplitStay before building
RUN cd SplitStay && mvn clean package -DskipTests

# Run phase
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/SplitStay/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
