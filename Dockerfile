FROM maven:3-eclipse-temurin-21-alpine AS mchain
COPY pom.xml /tmp/
COPY src/ /tmp/src/
WORKDIR /tmp/
RUN mvn clean package

FROM bitnami/tomcat:9.0
LABEL org.opencontainers.image.authors="m.buechner@dnb.de"
COPY --from=mchain /tmp/target/dzp-fcs.war /opt/bitnami/tomcat/webapps/ROOT.war
USER root
RUN apt-get update && \
    apt-get -y upgrade && \ 
    sed -i 's#<Connector port="8080"#<Connector port="8080" compression="on"#' /opt/bitnami/tomcat/conf/server.xml
USER 1001
EXPOSE 8080
