FROM maven:3-openjdk-11-slim AS MAVEN_CHAIN
COPY pom.xml setenv.sh /tmp/
COPY src/ /tmp/src/
WORKDIR /tmp/
RUN sed -i 's#<url-pattern>/\*</url-pattern>#<url-pattern>${URLPATTERN}</url-pattern>#' src/main/webapp/WEB-INF/web.xml && \
  mvn package

FROM tomcat:9-jre11
MAINTAINER Michael Büchner <m.buechner@dnb.de>
COPY --from=MAVEN_CHAIN /tmp/target/dzp-fcs.war ${CATALINA_HOME}/webapps/ROOT.war
COPY --from=MAVEN_CHAIN /tmp/setenv.sh ${CATALINA_HOME}/bin/

EXPOSE 8080
