FROM maven:3-openjdk-11-slim AS MAVEN_CHAIN
COPY pom.xml /tmp/
COPY src/ /tmp/src/
WORKDIR /tmp/
RUN sed -i 's#<url-pattern>/\*</url-pattern>#<url-pattern>${DZP_FCS_PATH_PREFIX}</url-pattern>#' src/main/webapp/WEB-INF/web.xml && \
  mvn package

FROM bitnami/tomcat:9.0
MAINTAINER Michael Büchner <m.buechner@dnb.de>
COPY --from=MAVEN_CHAIN /tmp/target/dzp-fcs.war /opt/bitnami/tomcat/webapps/ROOT.war
RUN { \
	echo ""; \
	echo "# Setting variable url-pattern in web.xml"; \
	echo "if [ -z \"\${DZP_FCS_PATH_PREFIX}\" ]; then"; \
	echo "DZP_FCS_PATH_PREFIX=\"/*\""; \
	echo "fi"; \
	echo "export CATALINA_OPTS=\"\$CATALINA_OPTS -DDZP_FCS_PATH_PREFIX=\${DZP_FCS_PATH_PREFIX}\""; \
} >> /opt/bitnami/tomcat/bin/setenv.sh && \
sed -i 's#<Connector port="8080"#<Connector port="8080" compression="on"#' /opt/bitnami/tomcat/conf/server.xml

EXPOSE 8080
