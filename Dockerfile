FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY src src
RUN chmod +x gradlew
RUN ./gradlew clean bootJar -x test
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "build/libs/driving-theory-backend-0.0.1-SNAPSHOT.jar"]