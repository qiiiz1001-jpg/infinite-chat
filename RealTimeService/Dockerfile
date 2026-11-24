FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/logs/nacos /app/.nacos /app/nacos/naming \
    && chown -R app:app /app
COPY --chown=app:app RealTimeService/target-maven/RealTimeService-0.0.1-SNAPSHOT.jar /app/app.jar

USER app
ENV TZ=Asia/Shanghai
EXPOSE 8102 9101
ENTRYPOINT ["java","-jar","/app/app.jar"]
