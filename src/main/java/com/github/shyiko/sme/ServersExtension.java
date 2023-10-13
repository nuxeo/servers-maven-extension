/*
 * Copyright 2013 Stanley Shyiko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.shyiko.sme;

import java.lang.reflect.Method;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.bridge.MavenRepositorySystem;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Repository;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.MirrorSelector;
import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Server;
import org.codehaus.plexus.PlexusConstants;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.context.Context;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.Contextualizable;

/**
 * @author <a href="mailto:stanley.shyiko@gmail.com">Stanley Shyiko</a>
 */
@Component(role = AbstractMavenLifecycleParticipant.class)
public class ServersExtension extends AbstractMavenLifecycleParticipant implements Contextualizable {

    private static final String REPOSITORIES = "repositories";

    private static final String SERVERS = "servers";

    private static final String SECURITY_DISPATCHER_CLASS_NAME = "org.sonatype.plexus.components.sec.dispatcher.SecDispatcher";

    private static final Map<String, String[]> FIELDS = new HashMap<>();
    {
        FIELDS.put(SERVERS, new String[] { "username", "password", "passphrase", "privateKey", "filePermissions",
                "directoryPermissions" });
        FIELDS.put(REPOSITORIES, new String[] { "url" });
    }

    private PlexusContainer container;

    @Inject
    MirrorSelector mirrors;

    public void contextualize(final Context context) throws ContextException {
        container = (PlexusContainer) context.get(PlexusConstants.PLEXUS_KEY);
    }

    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        MojoExecution mojoExecution = new MojoExecution(new MojoDescriptor());
        ExpressionEvaluator expressionEvaluator = new PluginParameterExpressionEvaluator(session, mojoExecution);
        Properties userProperties = session.getUserProperties();
        Map<String, String> properties = new HashMap<String, String>();
        try {
            collectRepositories(session, expressionEvaluator, userProperties, properties);
            collectServers(session, expressionEvaluator, userProperties, properties);
        } catch (Exception e) {
            throw new MavenExecutionException("Failed to expose settings.servers.*", e);
        }
        for (MavenProject project : session.getProjects()) {
            project.getProperties().putAll(properties);
        }
    }

    private void collectRepositories(MavenSession session, ExpressionEvaluator expressionEvaluator,
            Properties userProperties, Map<String, String> properties) throws Exception {
        MavenProject project = session.getCurrentProject();
        for (Repository repository : project.getRepositories()) {
            final String id = repository.getId();
            final String prefix = String.format("project.%s.%s.", REPOSITORIES, id);

            // retrieve repository info
            for (String name : FIELDS.get(REPOSITORIES)) {
                properties.put(prefix + name,
                        (String) Repository.class.getMethod("get" + upperCaseFirstLetter(name)).invoke(repository));
            }

            // override with mirror URL if any
            final ArtifactRepository artifacts = MavenRepositorySystem.buildArtifactRepository(repository);
            final Mirror mirror = mirrors.getMirror(artifacts, session.getSettings().getMirrors());
            if (mirror != null) {
                properties.put(prefix + "url", mirror.getUrl());
            }
        }
    }

    private void collectServers(MavenSession session, ExpressionEvaluator expressionEvaluator,
            Properties userProperties, Map<String, String> properties) throws Exception {
        for (Server server : session.getSettings().getServers()) {
            String serverId = server.getId();
            for (String field : FIELDS.get(SERVERS)) {
                String[] aliases = getServerAliases(serverId, field);
                String fieldNameWithFirstLetterCapitalized = upperCaseFirstLetter(field);
                String fieldValue = (String) Server.class.getMethod("get" + fieldNameWithFirstLetterCapitalized)
                                                         .invoke(server);
                if (fieldValue != null) {
                    fieldValue = decryptInlinePasswords(fieldValue);
                }
                for (String alias : aliases) {
                    String userPropertyValue = userProperties.getProperty(alias);
                    if (userPropertyValue != null) {
                        fieldValue = userPropertyValue;
                        break;
                    }
                }
                String resolvedValue = (String) expressionEvaluator.evaluate(fieldValue);
                Server.class.getMethod("set" + fieldNameWithFirstLetterCapitalized, new Class[] { String.class })
                            .invoke(server, resolvedValue);
                if (resolvedValue != null) {
                    for (String alias : aliases) {
                        properties.put(alias, resolvedValue);
                    }
                }
            }
            String authValue = encodeBasicAuth(serverId, properties);
            for (String alias : getServerAliases(serverId, "auth")) {
                properties.put(alias, authValue);
            }
        }

    }

    private String encodeBasicAuth(String serverId, Map<String, String> props) {
        String value = String.format("%s:%s", props.getOrDefault("settings.servers." + serverId + ".username", ""),
                props.getOrDefault("settings.servers." + serverId + ".password", ""));

        return Base64.getEncoder().encodeToString(value.getBytes());
    }

    private String decryptInlinePasswords(String v) {
        Pattern p = Pattern.compile("(\\{[^\\}]+\\})");
        Matcher m = p.matcher(v);
        StringBuffer s = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(s, decryptPassword(m.group(1)));
        }
        m.appendTail(s);
        return s.toString();
    }

    private String decryptPassword(String password) {
        try {
            Class<?> securityDispatcherClass = container.getClass()
                                                        .getClassLoader()
                                                        .loadClass(SECURITY_DISPATCHER_CLASS_NAME);
            Object securityDispatcher = container.lookup(SECURITY_DISPATCHER_CLASS_NAME, "maven");
            Method decrypt = securityDispatcherClass.getMethod("decrypt", String.class);
            return ((String) decrypt.invoke(securityDispatcher, password)).replace("$", "\\$");
        } catch (Exception ignore) {
        }
        return password;
    }

    private String[] getServerAliases(String id, String field) {
        return new String[] { "settings.servers." + id + "." + field, "settings.servers.server." + id + "." + field, // legacy
                                                                                                                     // syntax,
                                                                                                                     // left
                                                                                                                     // for
                                                                                                                     // backward
                                                                                                                     // compatibility
        };
    }

    private String upperCaseFirstLetter(String string) {
        return Character.toUpperCase(string.charAt(0)) + string.substring(1);
    }
}
