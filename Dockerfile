FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN groupadd -g 1001 appuser && useradd -u 1001 -g appuser -M -s /usr/sbin/nologin appuser \
    && mkdir -p /data/noted/notes && chown -R appuser:appuser /data/noted /app
USER appuser
EXPOSE 8210
ENTRYPOINT ["java", "-jar", "app.jar"]
