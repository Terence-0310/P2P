# Relay Server cho Internet Mode của P2PFileShare
# Build:  docker build -t p2p-relay ./relay-server
# Chạy :  xem deploy/docker-compose.yml (kèm Caddy để có wss:// + chứng chỉ Let's Encrypt)

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre
RUN useradd --system --no-create-home relay
WORKDIR /app
COPY --from=build /src/target/p2p-relay-server.jar /app/relay.jar
USER relay
EXPOSE 8080
ENV RELAY_PORT=8080 \
    RELAY_BIND=0.0.0.0
# Giới hạn RAM của JVM: relay chỉ giữ dữ liệu đang chuyển trong hàng đợi có giới hạn
ENV JAVA_TOOL_OPTIONS="-Xmx256m"
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /health HTTP/1.0\r\n\r\n" >&3 && grep -q "ok" <&3'
ENTRYPOINT ["java", "-jar", "/app/relay.jar"]
