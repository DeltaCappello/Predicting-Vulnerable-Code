/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.geronimo.axis2.builder;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

import javax.xml.namespace.QName;
import javax.xml.ws.http.HTTPBinding;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.geronimo.axis2.client.Axis2ServiceReference;
import org.apache.geronimo.axis2.pojo.POJOWebServiceContainerFactoryGBean;
import org.apache.geronimo.common.DeploymentException;
import org.apache.geronimo.deployment.DeploymentContext;
import org.apache.geronimo.gbean.GBeanData;
import org.apache.geronimo.gbean.GBeanInfo;
import org.apache.geronimo.gbean.GBeanInfoBuilder;
import org.apache.geronimo.j2ee.deployment.Module;
import org.apache.geronimo.j2ee.deployment.WebModule;
import org.apache.geronimo.j2ee.deployment.WebServiceBuilder;
import org.apache.geronimo.j2ee.j2eeobjectnames.NameFactory;
import org.apache.geronimo.jaxws.JAXWSUtils;
import org.apache.geronimo.jaxws.PortInfo;
import org.apache.geronimo.jaxws.builder.EndpointInfoBuilder;
import org.apache.geronimo.jaxws.builder.JAXWSServiceBuilder;
import org.apache.geronimo.jaxws.builder.WsdlGenerator;
import org.apache.geronimo.jaxws.client.EndpointInfo;
import org.apache.geronimo.kernel.repository.Environment;
import org.apache.geronimo.xbeans.geronimo.naming.GerServiceRefType;
import org.apache.geronimo.xbeans.javaee.PortComponentRefType;
import org.apache.geronimo.xbeans.javaee.PortComponentType;
import org.apache.geronimo.xbeans.javaee.ServiceImplBeanType;
import org.apache.geronimo.xbeans.javaee.ServiceRefHandlerChainsType;
import org.apache.geronimo.xbeans.javaee.WebserviceDescriptionType;
import org.apache.geronimo.xbeans.javaee.WebservicesDocument;
import org.apache.geronimo.xbeans.javaee.WebservicesType;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;

/**
 * @version $Rev$ $Date$
 */
public class Axis2Builder extends JAXWSServiceBuilder {

    private static final Log log = LogFactory.getLog(Axis2Builder.class);
        
    public Axis2Builder(Environment defaultEnviroment) {
        super(defaultEnviroment);
    }
    
    public Axis2Builder(){
        super(null);
    }
    
    protected GBeanInfo getContainerFactoryGBeanInfo() {
        return POJOWebServiceContainerFactoryGBean.GBEAN_INFO;
    }
    
    protected Map<String, PortInfo> parseWebServiceDescriptor(InputStream in,
                                                              URL wsDDUrl,
                                                              JarFile moduleFile,
                                                              boolean isEJB,
                                                              Map correctedPortLocations)
            throws DeploymentException {

        log.debug("Parsing descriptor " + wsDDUrl);

        Map<String, PortInfo> map = null;
        XmlCursor cursor = null;

        try {
            XmlObject xobj = XmlObject.Factory.parse(in);
           
            cursor = xobj.newCursor();
            cursor.toStartDoc();
            cursor.toFirstChild();
            //the checking is needed as we also send JAX-RPC based webservices.xml here
            if ("http://java.sun.com/xml/ns/javaee".equals(cursor.getName().getNamespaceURI())) {
                WebservicesDocument wd = (WebservicesDocument)xobj.changeType(WebservicesDocument.type);
                WebservicesType wst = wd.getWebservices();

                for (WebserviceDescriptionType desc : wst.getWebserviceDescriptionArray()) {
                    String wsdlFile = null;
                    if (desc.getWsdlFile() != null) {
                        wsdlFile = getString(desc.getWsdlFile().getStringValue());
                    }

                    String serviceName = desc.getWebserviceDescriptionName().getStringValue();

                    for (PortComponentType port : desc.getPortComponentArray()) {

                        PortInfo portInfo = new PortInfo();
                        String serviceLink = null;
                        ServiceImplBeanType beanType = port.getServiceImplBean();
                        if (beanType.getEjbLink() != null) {
                            serviceLink = beanType.getEjbLink().getStringValue();
                        } else if (beanType.getServletLink().getStringValue() != null) {
                            serviceLink = beanType.getServletLink().getStringValue();
                        }
                        portInfo.setServiceLink(serviceLink);

                        if (port.getServiceEndpointInterface() != null) {
                            String sei = port.getServiceEndpointInterface().getStringValue();
                            portInfo.setServiceEndpointInterfaceName(sei);
                        }

                        String portName = port.getPortComponentName().getStringValue();
                        portInfo.setPortName(portName);

                        portInfo.setProtocolBinding(port.getProtocolBinding());
                        portInfo.setServiceName(serviceName);
                        portInfo.setWsdlFile(wsdlFile);

                        if (port.getEnableMtom() != null) {
                            portInfo.setEnableMTOM(port.getEnableMtom().getBooleanValue());
                        }

                        if (port.getHandlerChains() != null) {
                            StringBuffer chains = new StringBuffer("<handler-chains xmlns=\"http://java.sun.com/xml/ns/javaee\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">");
                            chains.append(port.getHandlerChains().xmlText());
                            chains.append("</handler-chains>");
                            portInfo.setHandlersAsXML(chains.toString());
                        }

                        if (port.getWsdlPort() != null) {
                            portInfo.setWsdlPort(port.getWsdlPort().getQNameValue());
                        }

                        if (port.getWsdlService() != null) {
                            portInfo.setWsdlService(port.getWsdlService().getQNameValue());
                        }

                        String location = (String) correctedPortLocations.get(serviceLink);
                        portInfo.setLocation(location);

                        if (map == null) {
                            map = new HashMap<String, PortInfo>();
                        }

                        map.put(serviceLink, portInfo);
                    }
                }
            } else {
                log.debug("Descriptor ignored (not a Java EE 5 descriptor)");
            }
            
            return map;
        } catch (FileNotFoundException e) {
            return Collections.emptyMap();
        } catch (IOException ex) {
            throw new DeploymentException("Unable to read " + wsDDUrl, ex);
        } catch (Exception ex) {
            throw new DeploymentException("Unknown deployment error", ex);
        } finally {
            try {
                cursor.dispose();
                in.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    public boolean configurePOJO(GBeanData targetGBean,
                                 String servletName,
                                 Module module,
                                 String seiClassName,
                                 DeploymentContext context)
        throws DeploymentException {
                
        boolean status = super.configurePOJO(targetGBean, servletName, module, seiClassName, context);
        if(!status) {
            return false;
        }       
                
        //change the URL
        Map sharedContext = ((WebModule) module).getSharedContext();
        String contextRoot = ((WebModule) module).getContextRoot();
        Map portInfoMap = (Map) sharedContext.get(getKey());
        PortInfo portInfo;
        
        if(portInfoMap != null && portInfoMap.get(servletName) != null){
            portInfo = (PortInfo) portInfoMap.get(servletName);
            processURLPattern(contextRoot, portInfo);
        }
        
        return status;
    }

    public Object createService(Class serviceInterface,
                                Class serviceReference,
                                URI wsdlURI,
                                QName serviceQName,
                                Map<Class, PortComponentRefType> portComponentRefMap,
                                ServiceRefHandlerChainsType handlerChains,
                                GerServiceRefType serviceRefType,
                                Module module,
                                ClassLoader cl) throws DeploymentException {
        EndpointInfoBuilder builder = new EndpointInfoBuilder(serviceInterface,
                serviceRefType, portComponentRefMap, module.getModuleFile(),
                wsdlURI, serviceQName);
        builder.build();

        wsdlURI = builder.getWsdlURI();
        serviceQName = builder.getServiceQName();
        Map<Object, EndpointInfo> seiInfoMap = builder.getEndpointInfo();

        String handlerChainsXML = null;
        try {
            handlerChainsXML = getHandlerChainAsString(handlerChains);
        } catch (IOException e) {
            // this should not happen
            log.warn("Failed to serialize handler chains", e);
        }

        String serviceReferenceName = (serviceReference == null) ? null : serviceReference.getName();
        return new Axis2ServiceReference(serviceInterface.getName(), serviceReferenceName,  wsdlURI,
                serviceQName, module.getModuleName(), handlerChainsXML, seiInfoMap);
    }

    private static String getHandlerChainAsString(ServiceRefHandlerChainsType handlerChains)
        throws IOException {
        String xml = null;
        if (handlerChains != null) {
            StringWriter w = new StringWriter();
            XmlOptions options = new XmlOptions();
            options.setSaveSyntheticDocumentElement(new QName("http://java.sun.com/xml/ns/javaee", "handler-chains")); 
            handlerChains.save(w, options);
            xml = w.toString();
        }
        return xml;
    }
    
    private static String getString(String in) {
        if (in != null) {
            in = in.trim();
            if (in.length() == 0) {
                return null;
            }
        }
        return in;
    }

    private void processURLPattern(String contextRoot, PortInfo portInfo) throws DeploymentException {
        //if the user specifies a url-pattern, set it here. 
        String oldup = portInfo.getLocation();
        if (oldup == null || oldup.length() == 0) { 
            //if we cannot grab a valid urlpattern, default it to the port-component-name.
            oldup = portInfo.getPortName();   
        } else {
            int i = oldup.indexOf(contextRoot);
            oldup = oldup.substring(i + contextRoot.length() + 1);
            oldup = oldup.trim();
            if (oldup.indexOf("*") > 0) {
                //uncomment this before we fix this issue.  workarond by assuming * is at the end.
                //throw new DeploymentException("Per JSR 109, the url-pattern should not contain an asterisk.");
                oldup = oldup.substring(0, oldup.length() - 1);
            } 
            //trim the forward slashes at the beginning or end.
            if (oldup.substring(0, 1).equalsIgnoreCase("/")) {
                oldup = oldup.substring(1);
            } 
            if (oldup.substring(oldup.length()-1).equalsIgnoreCase("/")) {
                oldup = oldup.substring(0, oldup.length() - 1);
            }
        
        } 
        portInfo.setLocation(oldup);
    }
        
    @Override
    protected void initialize(GBeanData targetGBean, Class serviceClass, PortInfo portInfo, Module module) 
        throws DeploymentException {
        if (isWsdlSet(portInfo, serviceClass)) {
            log.debug("Service " + portInfo.getServiceName() + " has WSDL.");
            return;
        }
        
        if (isHTTPBinding(portInfo, serviceClass)) {
            log.debug("Service " + portInfo.getServiceName() + " is HTTPBinding.  Only SOAP 1.1 or 1.2 is supported.");
            return;
        }
        
        log.debug("Service " + portInfo.getServiceName() + " does not have WSDL. Generating WSDL...");

        WsdlGenerator generator = new WsdlGenerator();
        generator.setAxis2SAAJ();
        
        // set wsdl service
        if (portInfo.getWsdlService() == null) {
            generator.setWsdlService(JAXWSUtils.getServiceQName(serviceClass));
        } else {
            generator.setWsdlService(portInfo.getWsdlService());
        }
        
        // set wsdl port
        if (portInfo.getWsdlPort() != null) {
            generator.setWsdlPort(portInfo.getWsdlPort());
        }
                
        String wsdlFile = generator.generateWsdl(module, serviceClass.getName(), module.getEarContext(), portInfo);
        portInfo.setWsdlFile(wsdlFile);
        
        log.debug("Generated " + wsdlFile + " for service " + portInfo.getServiceName());        
    }
    
    private boolean isWsdlSet(PortInfo portInfo, Class serviceClass) {
        return (portInfo.getWsdlFile() != null && !portInfo.getWsdlFile().trim().equals(""))
                || JAXWSUtils.containsWsdlLocation(serviceClass, serviceClass.getClassLoader());
    }
    
    private boolean isHTTPBinding(PortInfo portInfo, Class serviceClass) {
        String bindingURI = "";
        String bindingURIFromAnnot;
        
        if (portInfo.getProtocolBinding() != null) {
            bindingURI = JAXWSUtils.getBindingURI(portInfo.getProtocolBinding());
        }        
        bindingURIFromAnnot = JAXWSUtils.getBindingURIFromAnnot(serviceClass, serviceClass.getClassLoader());
        
        if (bindingURI != null && !bindingURI.trim().equals("")) {
            return bindingURI.equals(HTTPBinding.HTTP_BINDING);
        } else if (bindingURIFromAnnot != null && !bindingURIFromAnnot.trim().equals("")) {
            return bindingURIFromAnnot.equals(HTTPBinding.HTTP_BINDING);
        } 
        
        return false;  
    }
        
    public static final GBeanInfo GBEAN_INFO;

    static {
        GBeanInfoBuilder infoBuilder = GBeanInfoBuilder.createStatic(Axis2Builder.class, NameFactory.MODULE_BUILDER);
        infoBuilder.addInterface(WebServiceBuilder.class);
        infoBuilder.addAttribute("defaultEnvironment", Environment.class, true, true);
        infoBuilder.setConstructor(new String[]{"defaultEnvironment"});
        GBEAN_INFO = infoBuilder.getBeanInfo();
    }

    public static GBeanInfo getGBeanInfo() {
        return GBEAN_INFO;
    }
}
