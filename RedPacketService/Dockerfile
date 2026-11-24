FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/logs/nacos /app/.nacos /app/nacos/naming \
    && chown -R app:app /app
COPY --chown=app:app RedPacketService/target-maven/RedPacketService-0.0.1-SNAPSHOT.jar /app/app.jar

USER app
ENV TZ=Asia/Shanghai
EXPOSE 8103
ENTRYPOINT ["java","-jar","/app/app.jar"]
