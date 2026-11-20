FROM maven:3-amazoncorretto-25 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
ARG SOURCE_DATE_EPOCH
RUN if [ -n "${SOURCE_DATE_EPOCH}" ]; then \
      set -- "-Dproject.build.outputTimestamp=${SOURCE_DATE_EPOCH}"; \
    fi \
 && mvn -B -ntp "$@" package

# Tomcat 9 provides the javax.servlet API required by the WAR.
FROM tomcat:9-jre25-temurin-noble AS tomcat
RUN rm -rf "${CATALINA_HOME}/native-jni-lib" "${CATALINA_HOME}/webapps.dist" \
           "${CATALINA_HOME}/webapps/"*

FROM amazoncorretto:25
LABEL maintainer="Michael Büchner <m.buechner@dnb.de>"

ENV CATALINA_HOME=/usr/local/tomcat \
    DZP_FCS_CONTEXT_PATH=/
ENV PATH="${CATALINA_HOME}/bin:${PATH}"
WORKDIR ${CATALINA_HOME}
COPY --from=tomcat /usr/local/tomcat/ ./

# The entrypoint deploys this one WAR using the configured context path.
COPY --from=build /build/target/dzp-fcs.war /build/target/dzp-fcs-sbom.json ./dzp-fcs/
COPY config/docker-entrypoint.sh /usr/local/bin/dzp-fcs-entrypoint.sh

# Resolve connector and web.xml ${ENV_NAME:-default} values directly through Tomcat.
COPY config/tomcat/server.xml ./conf/server.xml
RUN sed -i 's/\r$//' /usr/local/bin/dzp-fcs-entrypoint.sh \
 && printf '\n%s\n' \
    'org.apache.tomcat.util.digester.PROPERTY_SOURCE=org.apache.tomcat.util.digester.EnvironmentPropertySource' \
    >> "${CATALINA_HOME}/conf/catalina.properties" \
 && mkdir -p logs temp webapps work \
 && chgrp -R 0 logs temp webapps work \
 && chmod -R g=u logs temp webapps work

EXPOSE 8080
USER 10001:0
ENTRYPOINT ["sh", "/usr/local/bin/dzp-fcs-entrypoint.sh"]
CMD ["catalina.sh", "run"]
