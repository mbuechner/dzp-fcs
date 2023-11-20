FROM maven:3-openjdk-11-slim AS MAVEN_CHAIN
COPY pom.xml setenv.sh /tmp/
COPY src/ /tmp/src/
WORKDIR /tmp/
RUN sed -i 's#<url-pattern>/\*</url-pattern>#<url-pattern>${URLPATTERN}</url-pattern>#' src/main/webapp/WEB-INF/web.xml && \
  mvn package

FROM tomcat:9-jdk11-openjdk-slim-bullseye
MAINTAINER Michael Büchner <m.buechner@dnb.de>
ENV RUN_USER tomcat
ENV RUN_GROUP 0
RUN groupadd -r ${RUN_GROUP} && \
  useradd -g ${RUN_GROUP} -d ${CATALINA_HOME} -s /bin/bash ${RUN_USER};
COPY --from=MAVEN_CHAIN --chown=${RUN_USER}:${RUN_GROUP} /tmp/target/dzp-fcs.war ${CATALINA_HOME}/webapps/ROOT.war
COPY --from=MAVEN_CHAIN --chown=${RUN_USER}:${RUN_GROUP} /tmp/setenv.sh ${CATALINA_HOME}/bin/
RUN mkdir -p /usr/local/tomcat/conf/Catalina/localhost && \
  chown -R ${RUN_USER}:${RUN_GROUP} ${CATALINA_HOME} && \
  chmod -R 777 ${CATALINA_HOME}/webapps /usr/local/tomcat/conf/Catalina;

EXPOSE 8080
