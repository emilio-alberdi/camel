/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.maven;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.camel.maven.helper.CoverageHelper;
import org.apache.camel.maven.helper.EndpointHelper;
import org.apache.camel.maven.model.CoverageNode;
import org.apache.camel.parser.RouteBuilderParser;
import org.apache.camel.parser.model.CamelEndpointDetails;
import org.apache.camel.parser.model.CamelNodeDetails;
import org.apache.camel.parser.model.CamelRouteDetails;
import org.apache.camel.parser.model.CamelSimpleExpressionDetails;
import org.apache.camel.util.KeyValueHolder;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.mojo.exec.AbstractExecMojo;
import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.JavaType;
import org.jboss.forge.roaster.model.source.JavaClassSource;

/**
 * Performs route coverage reports after running Camel unit tests with camel-test modules
 *
 * @goal coverage
 * @threadSafe
 */
public class CoverageMojo extends AbstractExecMojo {

    /**
     * The maven project.
     *
     * @parameter property="project"
     * @required
     * @readonly
     */
    protected MavenProject project;

    /**
     * Whether to fail if a route was not fully covered
     *
     * @parameter property="camel.failOnError"
     *            default-value="false"
     */
    private boolean failOnError;

    /**
     * Whether to include test source code
     *
     * @parameter property="camel.includeTest"
     *            default-value="false"
     */
    private boolean includeTest;

    /**
     * To filter the names of java and xml files to only include files matching any of the given list of patterns (wildcard and regular expression).
     * Multiple values can be separated by comma.
     *
     * @parameter property="camel.includes"
     */
    private String includes;

    /**
     * To filter the names of java and xml files to exclude files matching any of the given list of patterns (wildcard and regular expression).
     * Multiple values can be separated by comma.
     *
     * @parameter property="camel.excludes"
     */
    private String excludes;

    // CHECKSTYLE:OFF
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        List<CamelEndpointDetails> endpoints = new ArrayList<>();
        List<CamelSimpleExpressionDetails> simpleExpressions = new ArrayList<>();
        List<CamelRouteDetails> routeIds = new ArrayList<>();
        Set<File> javaFiles = new LinkedHashSet<File>();
        Set<File> xmlFiles = new LinkedHashSet<File>();

        // find all java route builder classes
        List list = project.getCompileSourceRoots();
        for (Object obj : list) {
            String dir = (String) obj;
            findJavaFiles(new File(dir), javaFiles);
        }
        // find all xml routes
        list = project.getResources();
        for (Object obj : list) {
            Resource dir = (Resource) obj;
            findXmlFiles(new File(dir.getDirectory()), xmlFiles);
        }

        if (includeTest) {
            list = project.getTestCompileSourceRoots();
            for (Object obj : list) {
                String dir = (String) obj;
                findJavaFiles(new File(dir), javaFiles);
            }
            list = project.getTestResources();
            for (Object obj : list) {
                Resource dir = (Resource) obj;
                findXmlFiles(new File(dir.getDirectory()), xmlFiles);
            }
        }

        List<CamelNodeDetails> routeTrees = new ArrayList<>();

        for (File file : javaFiles) {
            if (matchFile(file)) {
                try {

                    // parse the java source code and find Camel RouteBuilder classes
                    String fqn = file.getPath();
                    String baseDir = ".";
                    JavaType out = Roaster.parse(file);
                    // we should only parse java classes (not interfaces and enums etc)
                    if (out != null && out instanceof JavaClassSource) {
                        JavaClassSource clazz = (JavaClassSource) out;
                        List<CamelNodeDetails> result = RouteBuilderParser.parseRouteBuilderTree(clazz, baseDir, fqn, true);
                        routeTrees.addAll(result);
                    }
                } catch (Exception e) {
                    getLog().warn("Error parsing java file " + file + " code due " + e.getMessage(), e);
                }
            }
        }
        for (File file : xmlFiles) {
            if (matchFile(file)) {
                try {
                    // TODO: implement me
                } catch (Exception e) {
                    getLog().warn("Error parsing xml file " + file + " code due " + e.getMessage(), e);
                }
            }
        }

        getLog().info("Discovered " + routeTrees.size() + " routes");

        // skip any routes which has no route id assigned

        long anonymous = routeTrees.stream().filter(t -> t.getRouteId() == null).count();
        if (anonymous > 0) {
            getLog().warn("Discovered " + anonymous + " anonymous routes. Add route ids to these routes for route coverage support");
        }

        final AtomicInteger notCovered = new AtomicInteger();

        routeTrees = routeTrees.stream().filter(t -> t.getRouteId() != null).collect(Collectors.toList());
        for (CamelNodeDetails t : routeTrees) {
            String routeId = t.getRouteId();
            String fileName = asRelativeFile(t.getFileName());

            // grab dump data for the route
            try {
                List<KeyValueHolder<String, Integer>> coverageData = CoverageHelper.parseDumpRouteCoverageByRouteId("target/camel-route-coverage", routeId);
                if (coverageData.isEmpty()) {
                    getLog().warn("No route coverage data found for route: " + routeId
                        + ". Make sure to enable route coverage in your unit tests and assign unique route ids to your routes. Also remember to run unit tests first.");
                } else {
                    List<CoverageNode> coverage = gatherRouteCoverageSummary(t, coverageData);
                    String out = templateCoverageData(fileName, routeId, coverage, notCovered);
                    getLog().info("Route coverage summary:\n\n" + out);
                    getLog().info("");
                }

            } catch (Exception e) {
                throw new MojoExecutionException("Error during gathering route coverage data for route: " + routeId, e);
            }
        }

        if (failOnError && notCovered.get() > 0) {
            throw new MojoExecutionException("There are " + notCovered.get() + " route(s) not fully covered!");
        }
    }
    // CHECKSTYLE:ON

    @SuppressWarnings("unchecked")
    private String templateCoverageData(String fileName, String routeId, List<CoverageNode> model, AtomicInteger notCovered) throws MojoExecutionException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintStream sw = new PrintStream(bos);

        sw.println("File: " + fileName);
        sw.println("Route: " + routeId);
        sw.println();
        sw.println(String.format("%8s   %8s   %s", "Line #", "Count", "Route"));
        sw.println(String.format("%8s   %8s   %s", "------", "-----", "-----"));

        int covered = 0;
        for (CoverageNode node : model) {
            if (node.getCount() > 0) {
                covered++;
            }
            String pad = padString(node.getLevel());
            sw.println(String.format("%8s   %8s   %s", node.getLineNumber(), node.getCount(), pad + node.getName()));
        }

        if (covered != model.size()) {
            // okay here is a route that was not fully covered
            notCovered.incrementAndGet();
        }

        // calculate percentage of route coverage (must use double to have decimals)
        double percentage = ((double) covered / (double) model.size()) * 100;
        sw.println();
        sw.println("Coverage: " + covered + " out of " + model.size() + " (" + String.format("%.1f", percentage) + "%)");
        sw.println();

        return bos.toString();
    }

    private static List<CoverageNode> gatherRouteCoverageSummary(CamelNodeDetails route, List<KeyValueHolder<String, Integer>> coverageData) {
        List<CoverageNode> answer = new ArrayList<>();

        Iterator<KeyValueHolder<String, Integer>> it = coverageData.iterator();
        AtomicInteger level = new AtomicInteger();
        gatherRouteCoverageSummary(route, it, level, answer);
        return answer;
    }

    private static void gatherRouteCoverageSummary(CamelNodeDetails node, Iterator<KeyValueHolder<String, Integer>> it, AtomicInteger level, List<CoverageNode> answer) {
        CoverageNode data = new CoverageNode();
        data.setName(node.getName());
        data.setLineNumber(Integer.valueOf(node.getLineNumber()));
        data.setLevel(level.get());

        // add data
        answer.add(data);

        // find count
        boolean found = false;
        while (!found && it.hasNext()) {
            KeyValueHolder<String, Integer> holder = it.next();
            found = holder.getKey().equals(node.getName());
            if (found) {
                data.setCount(holder.getValue());
            }
        }

        if (node.getOutputs() != null) {
            level.addAndGet(1);
            for (CamelNodeDetails child : node.getOutputs()) {
                gatherRouteCoverageSummary(child, it, level, answer);
            }
            level.addAndGet(-1);
        }
    }

    private static String padString(int level) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
        return sb.toString();
    }

    private void findJavaFiles(File dir, Set<File> javaFiles) {
        File[] files = dir.isDirectory() ? dir.listFiles() : null;
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".java")) {
                    javaFiles.add(file);
                } else if (file.isDirectory()) {
                    findJavaFiles(file, javaFiles);
                }
            }
        }
    }

    private void findXmlFiles(File dir, Set<File> xmlFiles) {
        File[] files = dir.isDirectory() ? dir.listFiles() : null;
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".xml")) {
                    xmlFiles.add(file);
                } else if (file.isDirectory()) {
                    findXmlFiles(file, xmlFiles);
                }
            }
        }
    }

    private boolean matchFile(File file) {
        if (excludes == null && includes == null) {
            return true;
        }

        // exclude take precedence
        if (excludes != null) {
            for (String exclude : excludes.split(",")) {
                exclude = exclude.trim();
                // try both with and without directory in the name
                String fqn = stripRootPath(asRelativeFile(file.getAbsolutePath()));
                boolean match = EndpointHelper.matchPattern(fqn, exclude) || EndpointHelper.matchPattern(file.getName(), exclude);
                if (match) {
                    return false;
                }
            }
        }

        // include
        if (includes != null) {
            for (String include : includes.split(",")) {
                include = include.trim();
                // try both with and without directory in the name
                String fqn = stripRootPath(asRelativeFile(file.getAbsolutePath()));
                boolean match = EndpointHelper.matchPattern(fqn, include) || EndpointHelper.matchPattern(file.getName(), include);
                if (match) {
                    return true;
                }
            }
            // did not match any includes
            return false;
        }

        // was not excluded nor failed include so its accepted
        return true;
    }

    private String asRelativeFile(String name) {
        String answer = name;

        String base = project.getBasedir().getAbsolutePath();
        if (name.startsWith(base)) {
            answer = name.substring(base.length());
            // skip leading slash for relative path
            if (answer.startsWith(File.separator)) {
                answer = answer.substring(1);
            }
        }
        return answer;
    }

    private String stripRootPath(String name) {
        // strip out any leading source / resource directory

        List list = project.getCompileSourceRoots();
        for (Object obj : list) {
            String dir = (String) obj;
            dir = asRelativeFile(dir);
            if (name.startsWith(dir)) {
                return name.substring(dir.length() + 1);
            }
        }
        list = project.getTestCompileSourceRoots();
        for (Object obj : list) {
            String dir = (String) obj;
            dir = asRelativeFile(dir);
            if (name.startsWith(dir)) {
                return name.substring(dir.length() + 1);
            }
        }
        List resources = project.getResources();
        for (Object obj : resources) {
            Resource resource = (Resource) obj;
            String dir = asRelativeFile(resource.getDirectory());
            if (name.startsWith(dir)) {
                return name.substring(dir.length() + 1);
            }
        }
        resources = project.getTestResources();
        for (Object obj : resources) {
            Resource resource = (Resource) obj;
            String dir = asRelativeFile(resource.getDirectory());
            if (name.startsWith(dir)) {
                return name.substring(dir.length() + 1);
            }
        }

        return name;
    }

    private static String asPackageName(String name) {
        return name.replace(File.separator, ".");
    }

    private static String asSimpleClassName(String className) {
        int dot = className.lastIndexOf('.');
        if (dot > 0) {
            return className.substring(dot + 1);
        } else {
            return className;
        }
    }
}
