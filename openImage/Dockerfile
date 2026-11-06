# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN sed -i 's#https://dl-cdn.alpinelinux.org/alpine#https://mirrors.aliyun.com/alpine#g' /etc/apk/repositories \
    && apk add --no-cache curl vips-tools vips-heif
COPY --from=build /app/target/openimage-0.1.0.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
