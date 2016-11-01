/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.action.actions;

import org.orbeon.dom.Element;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.externalcontext.ExternalContext;
import org.orbeon.oxf.util.IndentedLogger;
import org.orbeon.oxf.util.NetUtils;
import org.orbeon.oxf.xforms.BindingContext;
import org.orbeon.oxf.xforms.XFormsConstants;
import org.orbeon.oxf.xforms.XFormsContainingDocument;
import org.orbeon.oxf.xforms.XFormsUtils;
import org.orbeon.oxf.xforms.action.XFormsAction;
import org.orbeon.oxf.xforms.action.XFormsActionInterpreter;
import org.orbeon.oxf.xforms.model.DataModel;
import org.orbeon.oxf.xforms.xbl.Scope;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.saxon.om.Item;

/**
 * 10.1.8 The load Element
 */
public class XFormsLoadAction extends XFormsAction {
    public void execute(XFormsActionInterpreter actionInterpreter, Element actionElement,
                        Scope actionScope, boolean hasOverriddenContext, Item overriddenContext) {

        final XFormsContainingDocument containingDocument = actionInterpreter.containingDocument();

        final String resourceAttributeValue = actionElement.attributeValue(XFormsConstants.RESOURCE_QNAME);

        final String showAttribute;
        {
            final String rawShowAttribute = actionInterpreter.resolveAVT(actionElement, "show");
            showAttribute = (rawShowAttribute == null) ? "replace" : rawShowAttribute;
            if (!("replace".equals(showAttribute) || "new".equals(showAttribute)))
                throw new OXFException("Invalid value for 'show' attribute on xf:load element: " + showAttribute);
        }
        final boolean doReplace = "replace".equals(showAttribute);
        final String target = actionInterpreter.resolveAVT(actionElement, XFormsConstants.XXFORMS_TARGET_QNAME);
        final String urlType = actionInterpreter.resolveAVT(actionElement, XMLConstants.FORMATTING_URL_TYPE_QNAME);
        final boolean urlNorewrite = XFormsUtils.resolveUrlNorewrite(actionElement);
        final boolean isShowProgress = !"false".equals(actionInterpreter.resolveAVT(actionElement, XFormsConstants.XXFORMS_SHOW_PROGRESS_QNAME));

        // "If both are present, the action has no effect."
        final BindingContext bindingContext = actionInterpreter.actionXPathContext().getCurrentBindingContext();
        if (bindingContext.newBind() && resourceAttributeValue != null)
            return;

        if (bindingContext.newBind()) {
            // Use single-node binding
            final String tempValue = DataModel.getValue(bindingContext.getSingleItem());
            if (tempValue != null) {
                final String encodedValue = NetUtils.encodeHRRI(tempValue, true);
                resolveStoreLoadValue(containingDocument, actionElement, doReplace, encodedValue, target, urlType, urlNorewrite, isShowProgress);
            } else {
                // The action is a NOP if it's not bound to a node
            }
            // NOTE: We are supposed to throw an xforms-link-error in case of failure. Can we do it?
        } else if (resourceAttributeValue != null) {
            // Use resource attribute

            // NOP if there is an AVT but no context node
            if (bindingContext.getSingleItem() == null && XFormsUtils.maybeAVT(resourceAttributeValue))
                return;

            // Resolve AVT
            final String resolvedResource = actionInterpreter.resolveAVTProvideValue(actionElement, resourceAttributeValue);
            if (resolvedResource == null) {
                final IndentedLogger indentedLogger = actionInterpreter.indentedLogger();
                if (indentedLogger.isDebugEnabled())
                    indentedLogger.logDebug("xf:load", "resource AVT returned an empty sequence, ignoring action",
                        "resource", resourceAttributeValue);
                return;
            }

            final String encodedResource = NetUtils.encodeHRRI(resolvedResource, true);
            resolveStoreLoadValue(containingDocument, actionElement, doReplace, encodedResource, target, urlType, urlNorewrite, isShowProgress);
            // NOTE: We are supposed to throw an xforms-link-error in case of failure. Can we do it?
        } else {
            // "Either the single node binding attributes, pointing to a URI in the instance
            // data, or the linking attributes are required."
            throw new OXFException("Missing 'resource' or 'ref' attribute on xf:load element.");
        }
    }

    public static void resolveStoreLoadValue(XFormsContainingDocument containingDocument,
                                             Element currentElement, boolean doReplace, String value, String target,
                                             String urlType, boolean urlNorewrite, boolean isShowProgress) {
        final String externalURL;
        if (value.startsWith("#") || urlNorewrite) {
            // Keep value unchanged if it's just a fragment or if we are explicitly disabling rewriting
            // TODO: Not clear what happens in portlet mode: does norewrite make any sense?
            externalURL = value;
        } else {
            // URL must be resolved
            if ("resource".equals(urlType)) {
                // Load as resource URL
                externalURL = XFormsUtils.resolveResourceURL(containingDocument, currentElement, value,
                        ExternalContext.Response.REWRITE_MODE_ABSOLUTE_PATH_OR_RELATIVE);
            } else {
                // Load as render URL

                // Cases for `show="replace"` and `render` URLs:
                //
                // 1. Servlet in non-embedded mode
                //     1. Upon initialization
                //         - URL is rewritten to absolute URL
                //         - `XFormsToXHTML` calls `getResponse.sendRedirect()`
                //             - just use the absolute `location` URL without any further processing
                //     2. Upon Ajax request
                //         - URL is rewritten to absolute URL
                //         - `xxf:load` sent to client in Ajax response
                //         - client does `window.location.href = ...` or `window.open()`
                // 2. Servlet in embedded mode (with client proxy portlet or embedding API)
                //     1. Upon initialization
                //         - URL is first rewritten to a absolute path without context (resolving of `resolveXMLBase`)
                //         - URL is not WSRP-encoded
                //         - `XFormsToXHTML` calls `getResponse.sendRedirect()`
                //         - `ServletExternalContext.getResponse.sendRedirect()`
                //             - rewrite the path to an absolute path including context
                //             - add `orbeon-embeddable=true`
                //         - embedding client performs HTTP redirect
                //     2. Upon Ajax request
                //         - URL is WSRP-encoded
                //         - `xxf:load` sent to client in Ajax response
                //         - client does `window.location.href = ...` or `window.open()`
                // 3. Portlet
                //     1. Upon initialization
                //         - not handled (https://github.com/orbeon/orbeon-forms/issues/2617)
                //     2. Upon Ajax
                //         - perform a "two-pass load"
                //         - URL is first rewritten to a absolute path without context (resolving of `resolveXMLBase`)
                //         - URL is not WSRP-encoded
                //         - `XFormsServer` adds a server event with `xxforms-load` dispatched to `#document`
                //         - client performs form submission (`action`) which includes server events
                //         - server dispatches incoming `xxforms-load` to `XXFormsRootControl`
                //         - `XXFormsRootControl` performs `getResponse.sendRedirect()`
                //         - `OrbeonPortlet` creates `Redirect()` object
                //         - `BufferedPortlet.bufferedProcessAction()` sets new render parameters
                //         - portlet then renders with new path set by the redirection
                //
                // Questions/suggestions:
                //
                // - why do we handle the Ajax case differently in embedded vs. portlet modes?
                // - it's unclear which parts of the rewriting must take place here vs. in `sendRedirect()`
                // - make clearer what's stored with `addLoadToRun()` (`location` is not clear enough)
                //
                final boolean skipRewrite;
                if (! containingDocument.isPortletContainer()) {
                    // Servlet container
                    if (! containingDocument.isEmbedded()) {
                        // Not embedded
                        skipRewrite = false;
                    } else {
                        // Embedded
                        if (containingDocument.isInitializing()) {
                            skipRewrite = true;
                        } else {
                            skipRewrite = false;
                        }
                    }
                } else {
                    // Portlet container
                    // NOTE: As of 2016-03-17, the initialization case will fail and the portlet will throw an exception.
                    skipRewrite = true;
                }

                externalURL = XFormsUtils.resolveRenderURL(containingDocument, currentElement, value, skipRewrite);
            }
        }

        // Force no progress indication if this is a JavaScript URL
        if (externalURL.startsWith("javascript:"))
            isShowProgress = false;

        containingDocument.addLoadToRun(externalURL, target, urlType, doReplace, isShowProgress);
    }
}
