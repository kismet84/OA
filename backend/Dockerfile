FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system oa && useradd --system --gid oa oa && mkdir /app/uploads && chown oa:oa /app/uploads
COPY --from=build /build/target/oa-1.0.0.jar app.jar
USER oa
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
