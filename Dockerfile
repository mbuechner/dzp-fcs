FROM maven:3-openjdk-11-slim AS MAVEN_CHAIN
COPY pom.xml setenv.sh logging.properties /tmp/
COPY src/ /tmp/src/
WORKDIR /tmp/
RUN sed -i 's#<url-pattern>/\*</url-pattern>#<url-pattern>${URLPATTERN}</url-pattern>#' src/main/webapp/WEB-INF/web.xml && \
  mvn package

FROM tomcat:9-jre11
MAINTAINER Michael Büchner <m.buechner@dnb.de>
COPY --from=MAVEN_CHAIN /tmp/target/dzp-fcs.war ${CATALINA_HOME}/webapps/ROOT.war
COPY --from=MAVEN_CHAIN /tmp/setenv.sh ${CATALINA_HOME}/bin/
COPY --from=MAVEN_CHAIN /tmp/logging.properties ${CATALINA_HOME}/conf/

EXPOSE 8080
