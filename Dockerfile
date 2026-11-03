FROM maven:3-eclipse-temurin-25-noble AS mchain
WORKDIR /tmp/
COPY . .
RUN mvn -B -ntp package

FROM tomcat:9-jre25-temurin-noble
LABEL maintainer="Michael Büchner <m.buechner@dnb.de>"

# Default-Webapps raus
RUN rm -rf "${CATALINA_HOME}/webapps/"*

# App kopieren
COPY --from=mchain /tmp/target/dzp-fcs.war ${CATALINA_HOME}/webapps/ROOT.war
# Resolve connector and web.xml ${ENV_NAME:-default} values directly through Tomcat.
COPY config/tomcat/server.xml ${CATALINA_HOME}/conf/server.xml
RUN printf '\n%s\n' \
    'org.apache.tomcat.util.digester.PROPERTY_SOURCE=org.apache.tomcat.util.digester.EnvironmentPropertySource' \
    >> "${CATALINA_HOME}/conf/catalina.properties"

# OpenShift-Compatibility
RUN chgrp -R 0 "${CATALINA_HOME}" \
 && chmod -R g=u "${CATALINA_HOME}"

EXPOSE 8080
