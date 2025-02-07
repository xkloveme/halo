version: '3'
services:
  halo:
    image: halohub/halo:latest
    container_name: halo
    restart: always
    volumes:
      - ./data:/root/.halo2
    ports:
      - 8090:8090
    healthcheck:
      test: [ "CMD", "curl", "-f", "http://localhost:8090/actuator/health/readiness" ]
      interval: 30s
      timeout: 5s
      retries: 5
      start_period: 30s
    command:
      - --spring.r2dbc.url=r2dbc:pool:mysql://${PANEL_DB_HOST}:${HALO_DB_PORT}/${PANEL_DB_NAME}
      - --spring.r2dbc.username=${PANEL_DB_USER}
      - --spring.r2dbc.password=${PANEL_DB_USER_PASSWORD}
      - --spring.sql.init.platform=MySQL
      - --halo.external-url=https://blog.jixiaokang.com
      - --halo.security.initializer.superadminusername=${HALO_ADMIN}
      - --halo.security.initializer.superadminpassword=${HALO_ADMIN_PASSWORD}
    labels:
      createdBy: "Apps"
