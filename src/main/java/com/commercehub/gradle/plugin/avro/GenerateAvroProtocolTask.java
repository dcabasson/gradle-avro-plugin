/**
 * Copyright © 2013-2015 Commerce Technologies, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.commercehub.gradle.plugin.avro;

import org.apache.avro.compiler.idl.Idl;
import org.apache.avro.compiler.idl.ParseException;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.specs.NotSpec;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.util.GradleVersion;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.LinkedList;
import java.util.List;

import static com.commercehub.gradle.plugin.avro.Constants.IDL_EXTENSION;
import static com.commercehub.gradle.plugin.avro.Constants.PROTOCOL_EXTENSION;

/**
 * Task to convert Avro IDL files into Avro protocol files using {@link Idl}.
 */
@CacheableTask
public class GenerateAvroProtocolTask extends OutputDirTask {
    @TaskAction
    protected void process() {
        getLogger().info("Found {} files", getSource().getFiles().size());
        failOnUnsupportedFiles();
        processFiles();
    }

    private void failOnUnsupportedFiles() {
        FileCollection unsupportedFiles = filterSources(new NotSpec<>(new FileExtensionSpec(IDL_EXTENSION)));
        if (!unsupportedFiles.isEmpty()) {
            throw new GradleException(
                    String.format("Unsupported file extension for the following files: %s", unsupportedFiles));
        }
    }

    private void processFiles() {
        int processedFileCount = 0;
        ClassLoader loader = getRuntimeClassLoader(getProject());
        for (File sourceFile : filterSources(new FileExtensionSpec(IDL_EXTENSION))) {
            processIDLFile(sourceFile, loader);
            processedFileCount++;
        }
        setDidWork(processedFileCount > 0);
    }

    private void processIDLFile(File idlFile, ClassLoader loader) {
        getLogger().info("Processing {}", idlFile);
        File protoFile = new File(getOutputDir(),
                FilenameUtils.getBaseName(idlFile.getName()) + "." + PROTOCOL_EXTENSION);
        Idl idl = null;
        try {
            idl = new Idl(idlFile, loader);
            String protoJson = idl.CompilationUnit().toString(true);
            writeJsonFile(protoFile, protoJson);
        } catch (IOException | ParseException ex) {
            throw new GradleException(String.format("Failed to compile IDL file %s", idlFile), ex);
        } finally {
            if (idl != null) {
                try {
                    idl.close();
                } catch (IOException ioe) {
                    // ignore
                }
            }
        }
    }

    private ClassLoader getRuntimeClassLoader(Project project) {
        List<URL> urls = new LinkedList<>();
        Configuration configuration = project.getConfigurations().getByName(getRuntimeConfigurationName());
        for (File file : configuration) {
            try {
                urls.add(file.toURI().toURL());
            } catch (MalformedURLException e) {
                getLogger().debug(e.getMessage());
            }
        }
        return urls.isEmpty() ? ClassLoader.getSystemClassLoader()
                : new URLClassLoader(urls.toArray(new URL[urls.size()]), ClassLoader.getSystemClassLoader());
    }

    /**
     * Backwards-compatible logic to return the appropriate configuration name for resolving the runtime classpath
     */
    private static String getRuntimeConfigurationName() {
        return GradleVersion.current().compareTo(GradleVersion.version("3.5")) >= 0
            ? Constants.RUNTIME_CLASSPATH_CONFIGURATION_NAME : Constants.RUNTIME_CONFIGURATION_NAME;
    }

    /**
     * Writes a file in a manner appropriate for a JSON file.  UTF-8 will be used, as it is the default encoding for JSON, and should be
     * maximally interoperable.
     *
     * @see <a href="https://tools.ietf.org/html/rfc7159#section-8.1">JSON Character Encoding</a>
     */
    private void writeJsonFile(File file, String data) throws IOException {
        FileUtils.writeStringToFile(file, data, Constants.UTF8_ENCODING);
    }
}
