FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/logs/nacos /app/.nacos /app/nacos/naming \
    && chown -R app:app /app
COPY --chown=app:app AiService/target-maven/AiService-0.0.1-SNAPSHOT.jar /app/app.jar
COPY --chown=app:app AiService/src/main/resources/docs /app/docs
ENV RAG_DOCS_PATH=/app/docs
USER app
ENV TZ=Asia/Shanghai
EXPOSE 8105
ENTRYPOINT ["java","-jar","/app/app.jar"]
