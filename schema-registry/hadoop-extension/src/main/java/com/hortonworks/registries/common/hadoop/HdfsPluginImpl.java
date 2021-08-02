/**
 * Copyright 2016-2021 Cloudera, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/
package com.hortonworks.registries.common.hadoop;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.ByteStreams;
import com.hortonworks.registries.common.FileStorageConfiguration;
import com.hortonworks.registries.common.util.FileStorage;
import com.hortonworks.registries.common.util.HdfsFileStorage;
import com.hortonworks.registries.common.FileStorageProperties;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.PrivilegedExceptionAction;

import static com.google.common.base.Preconditions.checkArgument;

/** This class is the implementation of the HDFS FileStorage plugin. It uses Hadoop classes
 * and therefore needs to be loaded on a separate classpath in order to not interfere with
 * the main classpath of Schema Registry. Please see readme.md of the subproject for more
 * information. */
public class HdfsPluginImpl extends AbstractHadoopPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(HdfsFileStorage.class);
    
    // the configuration keys

    private static final String CONFIG_KERBEROS_PRINCIPAL = "hdfs.kerberos.principal";
    private static final String CONFIG_KERBEROS_KEYTAB = "hdfs.kerberos.keytab";

    private String directory;
    private Configuration hdfsConfig;
    private URI fsUri;
    private boolean kerberosEnabled = false;
    

    @Override
    public void initialize(FileStorageConfiguration config) throws IOException {
        final FileStorageProperties props = config.getProperties();
        String fsUrl = props.getFsUrl();
        String kerberosPrincipal = props.getKerberosPrincipal();
        String keytabLocation = props.getKeytabLocation();
        this.directory = StringUtils.defaultIfBlank(props.getDirectory(), FileStorage.DEFAULT_DIR);

        this.hdfsConfig = new Configuration();

        // make sure fsUrl is set
        checkArgument(fsUrl != null, "fsUrl must be specified for HdfsFileStorage.");

        checkArgument(keytabLocation != null || kerberosPrincipal == null,
                "%s is needed when %s (== %s) is specified.",
                CONFIG_KERBEROS_KEYTAB, CONFIG_KERBEROS_PRINCIPAL, kerberosPrincipal);

        checkArgument(kerberosPrincipal != null || keytabLocation == null,
                "%s is needed when %s (== %s) is specified.",
                CONFIG_KERBEROS_PRINCIPAL, CONFIG_KERBEROS_KEYTAB, keytabLocation);

        if (kerberosPrincipal != null) {
            LOG.info("Logging in as kerberos principal {}", kerberosPrincipal);
            UserGroupInformation.loginUserFromKeytab(kerberosPrincipal, keytabLocation);
            kerberosEnabled = true;
        }

        if (StringUtils.isNotBlank(props.getAbfsImpl())) {
            hdfsConfig.set("fs.abfs.impl", props.getAbfsImpl());
        }
        if (StringUtils.isNotBlank(props.getAbfssImpl())) {
            hdfsConfig.set("fs.abfss.impl", props.getAbfssImpl());
        }

        directory = adjustDirectory(fsUrl, directory);
        fsUri = URI.create(fsUrl);

        LOG.info("Initialized with fsUrl={}, directory={}, kerberos principal={}", fsUrl, directory, kerberosPrincipal);
    }

    /**
     * Cloud storage filesystem url's usally contain paths. These should be prepended to the directory in order to work
     * properly.
     * @param fsUrl The HDFS or compatible filesystem URL
     * @param directory the directory to store hars
     * @return directory adjusted with path component in fsUrl
     */
    @VisibleForTesting
    static String adjustDirectory(String fsUrl, String directory) {
        String pathInFsUrl = URI.create(fsUrl).getPath();
        if (!(pathInFsUrl.endsWith("/") || directory.startsWith("/"))) {
            return pathInFsUrl + "/" + directory;
        } else if (pathInFsUrl.endsWith("/") && directory.startsWith("/")) {
            return pathInFsUrl + directory.substring(1);
        } else {
            return pathInFsUrl + directory;
        }
    }

    private boolean isKerberosEnabled() {
        return kerberosEnabled;
    }

    @Override
    public String uploadInternal(InputStream inputStream, String name) throws IOException {
        return execute(() -> {
            Path jarPath = new Path(directory, name);
            try (FSDataOutputStream outputStream = getFileSystem().create(jarPath, false)) {
                ByteStreams.copy(inputStream, outputStream);
            }

            return jarPath.toString();
        });
    }

    @Override
    public InputStream downloadInternal(String name) throws IOException {
        return execute(() -> {
            Path filePath = new Path(directory, name);
            return getFileSystem().open(filePath);
        });
    }

    @Override
    public boolean deleteInternal(String name) throws IOException {
        return execute(() -> getFileSystem().delete(new Path(directory, name), true));
    }

    @Override
    public boolean existsInternal(String name) throws IOException {
        return execute(() -> {
            Path path = new Path(directory, name);
            return getFileSystem().exists(path);
        });
    }

    private FileSystem getFileSystem() throws IOException {
        return FileSystem.get(fsUri, hdfsConfig);
    }

    private  <T> T execute(PrivilegedExceptionAction<T> action) throws IOException {
        try {
            if (isKerberosEnabled()) {
                UserGroupInformation ugi = UserGroupInformation.getLoginUser();
                LOG.info("doAs, logged in user: {}", ugi);
                return ugi.doAs(action);
            } else {
                return action.run();
            }
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException(ex);
        }
    }

}