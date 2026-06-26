# ---- Stage 1: Build ----
FROM eclipse-temurin:21-jdk AS build

RUN apt-get update && apt-get install -y python3 python3-pip && rm -rf /var/lib/apt/lists/*
RUN pip3 install requests --break-system-packages

WORKDIR /app

COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

COPY src/ src/
COPY scripts/ scripts/
RUN ./gradlew build -x test --no-daemon

# ---- Stage 2: Runtime ----
FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
