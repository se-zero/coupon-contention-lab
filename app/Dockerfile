# ── 빌드 단계 ───────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

# 의존성 레이어 분리 — src 변경만으로 의존성을 다시 받지 않게 한다
COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# ── 실행 단계 ───────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# 헬스체크용
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /build/build/libs/*.jar app.jar

EXPOSE 8080

# 힙을 고정한다 — 비교군 간 GC 조건을 동일하게 유지 (PROJECT_BRIEF 7장)
ENV JAVA_OPTS="-Xms1g -Xmx1g -XX:+UseG1GC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
