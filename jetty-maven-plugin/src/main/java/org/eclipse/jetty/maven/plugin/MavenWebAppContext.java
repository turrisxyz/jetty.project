//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jetty.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.quickstart.QuickStartConfiguration;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.FilterMapping;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.Configurations;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MavenWebAppContext
 *
 * Extends the WebAppContext to specialize for the maven environment. We pass in
 * the list of files that should form the classpath for the webapp when
 * executing in the plugin, and any jetty-env.xml file that may have been
 * configured.
 */
public class MavenWebAppContext extends WebAppContext
{
    private static final Logger LOG = LoggerFactory.getLogger(MavenWebAppContext.class);

    private static final String DEFAULT_CONTAINER_INCLUDE_JAR_PATTERN = ".*/javax.servlet-[^/]*\\.jar$|.*/jetty-servlet-api-[^/]*\\.jar$|.*javax.servlet.jsp.jstl-[^/]*\\.jar|.*taglibs-standard-impl-.*\\.jar";

    private static final String WEB_INF_CLASSES_PREFIX = "/WEB-INF/classes";

    private static final String WEB_INF_LIB_PREFIX = "/WEB-INF/lib";

    private File _classes = null;

    private File _testClasses = null;

    private final List<File> _webInfClasses = new ArrayList<>();

    private final List<File> _webInfJars = new ArrayList<>();

    private final Map<String, File> _webInfJarMap = new HashMap<String, File>();

    private List<File> _classpathFiles; // webInfClasses+testClasses+webInfJars

    private String _jettyEnvXml;

    private List<Overlay> _overlays;

    /**
     * Set the "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern" with
     * a pattern for matching jars on container classpath to scan. This is
     * analogous to the WebAppContext.setAttribute() call.
     */
    private String _containerIncludeJarPattern = null;

    /**
     * Set the "org.eclipse.jetty.server.webapp.WebInfIncludeJarPattern" with a
     * pattern for matching jars on webapp's classpath to scan. This is
     * analogous to the WebAppContext.setAttribute() call.
     */
    private String _webInfIncludeJarPattern = null;

    /**
     * If there is no maven-war-plugin config for ordering of the current
     * project in the sequence of overlays, use this to control whether the
     * current project is added first or last in list of overlaid resources
     */
    private boolean _baseAppFirst = true;

    public MavenWebAppContext() throws Exception
    {
        super();
        // Turn off copyWebInf option as it is not applicable for plugin.
        super.setCopyWebInf(false);        
    }

    public void setContainerIncludeJarPattern(String pattern)
    {
        _containerIncludeJarPattern = pattern;
    }

    public String getContainerIncludeJarPattern()
    {
        return _containerIncludeJarPattern;
    }

    public String getWebInfIncludeJarPattern()
    {
        return _webInfIncludeJarPattern;
    }

    public void setWebInfIncludeJarPattern(String pattern)
    {
        _webInfIncludeJarPattern = pattern;
    }

    public List<File> getClassPathFiles()
    {
        return this._classpathFiles;
    }

    public void setJettyEnvXml(String jettyEnvXml)
    {
        this._jettyEnvXml = jettyEnvXml;
    }

    public String getJettyEnvXml()
    {
        return this._jettyEnvXml;
    }

    public void setClasses(File dir)
    {
        _classes = dir;
    }

    public File getClasses()
    {
        return _classes;
    }

    public void setWebInfLib(List<File> jars)
    {
        _webInfJars.addAll(jars);
    }

    public void setTestClasses(File dir)
    {
        _testClasses = dir;
    }

    public File getTestClasses()
    {
        return _testClasses;
    }

    /**
     * Ordered list of wars to overlay on top of the current project. The list
     * may contain an overlay that represents the current project.
     *
     * @param overlays the list of overlays
     */
    public void setOverlays(List<Overlay> overlays)
    {
        _overlays = overlays;
    }

    /**
     * Set the name of the attribute that is used in each generated xml element
     * to indicate the source of the xml element (eg annotation, web.xml etc).
     *
     * @param name the name of the attribute to use.
     */
    public void setOriginAttribute(String name)
    {
        setAttribute(QuickStartConfiguration.ORIGIN_ATTRIBUTE, name);
    }

    /**
     * @return the originAttribute
     */
    public String getOriginAttribute()
    {
        Object attr = getAttribute(QuickStartConfiguration.ORIGIN_ATTRIBUTE);
        return attr == null ? null : attr.toString();
    }

    public List<Overlay> getOverlays()
    {
        return _overlays;
    }

    public void setBaseAppFirst(boolean value)
    {
        _baseAppFirst = value;
    }

    public boolean getBaseAppFirst()
    {
        return _baseAppFirst;
    }

    /**
     * This method is provided as a convenience for jetty maven plugin
     * configuration
     *
     * @param resourceBases Array of resources strings to set as a
     * {@link ResourceCollection}. Each resource string may be a
     * comma separated list of resources
     */
    public void setResourceBases(String[] resourceBases)
    {
        List<String> resources = new ArrayList<String>();
        for (String rl : resourceBases)
        {
            String[] rs = StringUtil.csvSplit(rl);
            for (String r : rs)
            {
                resources.add(r);
            }
        }
        setBaseResource(new ResourceCollection(resources.toArray(new String[resources.size()])));
    }

    public List<File> getWebInfLib()
    {
        return _webInfJars;
    }

    public List<File> getWebInfClasses()
    {
        return _webInfClasses;
    }

    @Override
    public void doStart() throws Exception
    {
        // Set up the pattern that tells us where the jars are that need
        // scanning

        // Allow user to set up pattern for names of jars from the container
        // classpath
        // that will be scanned - note that by default NO jars are scanned
        String tmp = _containerIncludeJarPattern;
        if (tmp == null || "".equals(tmp))
            tmp = (String)getAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN);

        tmp = addPattern(tmp, DEFAULT_CONTAINER_INCLUDE_JAR_PATTERN);
        setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN, tmp);

        // Allow user to set up pattern of jar names from WEB-INF that will be
        // scanned.
        // Note that by default ALL jars considered to be in WEB-INF will be
        // scanned - setting
        // a pattern restricts scanning
        if (_webInfIncludeJarPattern != null)
            setAttribute(MetaInfConfiguration.WEBINF_JAR_PATTERN, _webInfIncludeJarPattern);

        // Set up the classes dirs that comprises the equivalent of
        // WEB-INF/classes
        if (_testClasses != null)
            _webInfClasses.add(_testClasses);
        if (_classes != null)
            _webInfClasses.add(_classes);

        // Set up the classpath
        _classpathFiles = new ArrayList<>();
        _classpathFiles.addAll(_webInfClasses);
        _classpathFiles.addAll(_webInfJars);

        // Initialize map containing all jars in /WEB-INF/lib
        _webInfJarMap.clear();
        for (File file : _webInfJars)
        {
            // Return all jar files from class path
            String fileName = file.getName();
            if (fileName.endsWith(".jar"))
                _webInfJarMap.put(fileName, file);
        }

        // check for CDI
        initCDI();

        // CHECK setShutdown(false);
        super.doStart();
    }

    @Override
    protected Configurations newConfigurations()
    {
        Configurations configurations = super.newConfigurations();
        if (getJettyEnvXml() != null)
        {
            try
            {
                // inject configurations with config from maven plugin
                for (Configuration c : configurations)
                {
                    if (c instanceof EnvConfiguration)
                        ((EnvConfiguration)c).setJettyEnvResource(Resource.newResource(getJettyEnvXml()));
                }
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }

        return configurations;
    }

    @Override
    public void doStop() throws Exception
    {
        if (_classpathFiles != null)
            _classpathFiles.clear();
        _classpathFiles = null;

        _classes = null;
        _testClasses = null;

        if (_webInfJarMap != null)
            _webInfJarMap.clear();

        _webInfClasses.clear();
        _webInfJars.clear();

        // CHECK setShutdown(true);
        // just wait a little while to ensure no requests are still being
        // processed
        Thread.currentThread().sleep(500L);

        super.doStop();

        // remove all servlets and filters. This is because we will
        // re-appy any context xml file, which means they would potentially be
        // added multiple times.
        getServletHandler().setFilters(new FilterHolder[0]);
        getServletHandler().setFilterMappings(new FilterMapping[0]);
        getServletHandler().setServlets(new ServletHolder[0]);
        getServletHandler().setServletMappings(new ServletMapping[0]);
    }

    @Override
    public Resource getResource(String pathInContext) throws MalformedURLException
    {
        Resource resource = null;
        // Try to get regular resource
        resource = super.getResource(pathInContext);

        // If no regular resource exists check for access to /WEB-INF/lib or
        // /WEB-INF/classes
        if ((resource == null || !resource.exists()) && pathInContext != null && _classes != null)
        {
            // Canonicalize again to look for the resource inside /WEB-INF subdirectories.
            String uri = URIUtil.canonicalPath(pathInContext);
            if (uri == null)
                return null;

            try
            {
                // Replace /WEB-INF/classes with candidates for the classpath
                if (uri.startsWith(WEB_INF_CLASSES_PREFIX))
                {
                    if (uri.equalsIgnoreCase(WEB_INF_CLASSES_PREFIX) || uri.equalsIgnoreCase(WEB_INF_CLASSES_PREFIX + "/"))
                    {
                        // exact match for a WEB-INF/classes, so preferentially
                        // return the resource matching the web-inf classes
                        // rather than the test classes
                        if (_classes != null)
                            return Resource.newResource(_classes);
                        else if (_testClasses != null)
                            return Resource.newResource(_testClasses);
                    }
                    else
                    {
                        // try matching
                        Resource res = null;
                        int i = 0;
                        while (res == null && (i < _webInfClasses.size()))
                        {
                            String newPath = StringUtil.replace(uri, WEB_INF_CLASSES_PREFIX, _webInfClasses.get(i).getPath());
                            res = Resource.newResource(newPath);
                            if (!res.exists())
                            {
                                res = null;
                                i++;
                            }
                        }
                        return res;
                    }
                }
                else if (uri.startsWith(WEB_INF_LIB_PREFIX))
                {
                    // Return the real jar file for all accesses to
                    // /WEB-INF/lib/*.jar
                    String jarName = StringUtil.strip(uri, WEB_INF_LIB_PREFIX);
                    if (jarName.startsWith("/") || jarName.startsWith("\\"))
                        jarName = jarName.substring(1);
                    if (jarName.length() == 0)
                        return null;
                    File jarFile = _webInfJarMap.get(jarName);
                    if (jarFile != null)
                        return Resource.newResource(jarFile.getPath());

                    return null;
                }
            }
            catch (MalformedURLException e)
            {
                throw e;
            }
            catch (IOException e)
            {
                LOG.trace("IGNORED", e);
            }
        }
        return resource;
    }

    @Override
    public Set<String> getResourcePaths(String path)
    {
        // Try to get regular resource paths - this will get appropriate paths
        // from any overlaid wars etc
        Set<String> paths = super.getResourcePaths(path);

        if (path != null)
        {
            TreeSet<String> allPaths = new TreeSet<>();
            allPaths.addAll(paths);

            // add in the dependency jars as a virtual WEB-INF/lib entry
            if (path.startsWith(WEB_INF_LIB_PREFIX))
            {
                for (String fileName : _webInfJarMap.keySet())
                {
                    // Return all jar files from class path
                    allPaths.add(WEB_INF_LIB_PREFIX + "/" + fileName);
                }
            }
            else if (path.startsWith(WEB_INF_CLASSES_PREFIX))
            {
                int i = 0;

                while (i < _webInfClasses.size())
                {
                    String newPath = StringUtil.replace(path, WEB_INF_CLASSES_PREFIX, _webInfClasses.get(i).getPath());
                    allPaths.addAll(super.getResourcePaths(newPath));
                    i++;
                }
            }
            return allPaths;
        }
        return paths;
    }

    public String addPattern(String s, String pattern)
    {
        if (s == null)
            s = "";
        else
            s = s.trim();

        if (!s.contains(pattern))
        {
            if (s.length() != 0)
                s = s + "|";
            s = s + pattern;
        }

        return s;
    }

    public void initCDI()
    {
        Class<?> cdiInitializer = null;
        try
        {
            cdiInitializer = Thread.currentThread().getContextClassLoader().loadClass("org.eclipse.jetty.cdi.servlet.JettyWeldInitializer");
            Method initWebAppMethod = cdiInitializer.getMethod("initWebApp", new Class[]{WebAppContext.class});
            initWebAppMethod.invoke(null, new Object[]{this});
        }
        catch (ClassNotFoundException e)
        {
            LOG.debug("o.e.j.cdi.servlet.JettyWeldInitializer not found, no cdi integration available");
        }
        catch (NoSuchMethodException e)
        {
            LOG.warn("o.e.j.cdi.servlet.JettyWeldInitializer.initWebApp() not found, no cdi integration available");
        }
        catch (Exception e)
        {
            LOG.warn("Problem initializing cdi", e);
        }
    }
}
