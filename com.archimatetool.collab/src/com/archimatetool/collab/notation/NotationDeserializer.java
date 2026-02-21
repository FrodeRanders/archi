package com.archimatetool.collab.notation;

import java.util.List;

import com.archimatetool.collab.util.SimpleJson;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelBendpoint;
import com.archimatetool.model.IIconic;
import com.archimatetool.model.IDocumentable;
import com.archimatetool.model.INameable;

/**
 * Applies canonical notationJson payloads onto Archi diagram notation objects.
 */
public class NotationDeserializer {

    public void applyViewObjectNotation(Object viewObject, String notationJson) {
        if(!(viewObject instanceof IDiagramModelArchimateObject object) || notationJson == null || notationJson.isBlank()) {
            return;
        }

        Integer x = SimpleJson.readIntField(notationJson, "x");
        Integer y = SimpleJson.readIntField(notationJson, "y");
        Integer width = SimpleJson.readIntField(notationJson, "width");
        Integer height = SimpleJson.readIntField(notationJson, "height");
        if(x != null && y != null && width != null && height != null) {
            object.setBounds(x, y, width, height);
        }

        setIfPresentInt(notationJson, "type", object::setType);
        setIfPresentInt(notationJson, "alpha", object::setAlpha);
        setIfPresentInt(notationJson, "lineAlpha", object::setLineAlpha);
        setIfPresentInt(notationJson, "lineWidth", object::setLineWidth);
        setIfPresentInt(notationJson, "lineStyle", object::setLineStyle);
        setIfPresentInt(notationJson, "textAlignment", object::setTextAlignment);
        setIfPresentInt(notationJson, "textPosition", object::setTextPosition);
        setIfPresentInt(notationJson, "gradient", object::setGradient);
        setIfPresentInt(notationJson, "iconVisibleState", object::setIconVisibleState);
        setIfPresentBool(notationJson, "deriveElementLineColor", object::setDeriveElementLineColor);
        setIfPresentString(notationJson, "fillColor", object::setFillColor);
        setIfPresentString(notationJson, "lineColor", object::setLineColor);
        setIfPresentString(notationJson, "font", object::setFont);
        setIfPresentString(notationJson, "fontColor", object::setFontColor);
        setIfPresentString(notationJson, "iconColor", object::setIconColor);

        if(object instanceof IIconic iconic) {
            setIfPresentString(notationJson, "imagePath", iconic::setImagePath);
            setIfPresentInt(notationJson, "imagePosition", iconic::setImagePosition);
        }
        if(object instanceof INameable nameable) {
            setIfPresentString(notationJson, "name", nameable::setName);
        }
        if(object instanceof IDocumentable documentable) {
            setIfPresentString(notationJson, "documentation", documentable::setDocumentation);
        }
    }

    public void applyConnectionNotation(Object connection, String notationJson) {
        if(!(connection instanceof IDiagramModelArchimateConnection modelConnection) || notationJson == null || notationJson.isBlank()) {
            return;
        }

        setIfPresentInt(notationJson, "type", modelConnection::setType);
        setIfPresentBool(notationJson, "nameVisible", modelConnection::setNameVisible);
        setIfPresentInt(notationJson, "textAlignment", modelConnection::setTextAlignment);
        setIfPresentInt(notationJson, "textPosition", modelConnection::setTextPosition);
        setIfPresentInt(notationJson, "lineWidth", modelConnection::setLineWidth);
        setIfPresentString(notationJson, "lineColor", modelConnection::setLineColor);
        setIfPresentString(notationJson, "font", modelConnection::setFont);
        setIfPresentString(notationJson, "fontColor", modelConnection::setFontColor);
        if(modelConnection instanceof INameable nameable) {
            setIfPresentString(notationJson, "name", nameable::setName);
        }
        if(modelConnection instanceof IDocumentable documentable) {
            setIfPresentString(notationJson, "documentation", documentable::setDocumentation);
        }

        if(SimpleJson.hasField(notationJson, "bendpoints")) {
            List<String> bendpointJsons = SimpleJson.readArrayObjectElements(notationJson, "bendpoints");
            modelConnection.getBendpoints().clear();
            for(String bendpointJson : bendpointJsons) {
                IDiagramModelBendpoint bendpoint = IArchimateFactory.eINSTANCE.createDiagramModelBendpoint();
                setIfPresentInt(bendpointJson, "startX", bendpoint::setStartX);
                setIfPresentInt(bendpointJson, "startY", bendpoint::setStartY);
                setIfPresentInt(bendpointJson, "endX", bendpoint::setEndX);
                setIfPresentInt(bendpointJson, "endY", bendpoint::setEndY);
                modelConnection.getBendpoints().add(bendpoint);
            }
        }
    }

    private interface IntSetter {
        void set(int value);
    }

    private interface BoolSetter {
        void set(boolean value);
    }

    private interface StringSetter {
        void set(String value);
    }

    private void setIfPresentInt(String json, String key, IntSetter setter) {
        Integer value = SimpleJson.readIntField(json, key);
        if(value != null) {
            setter.set(value);
        }
    }

    private void setIfPresentBool(String json, String key, BoolSetter setter) {
        Boolean value = SimpleJson.readBooleanField(json, key);
        if(value != null) {
            setter.set(value);
        }
    }

    private void setIfPresentString(String json, String key, StringSetter setter) {
        if(SimpleJson.hasField(json, key)) {
            setter.set(SimpleJson.readStringField(json, key));
        }
    }
}
