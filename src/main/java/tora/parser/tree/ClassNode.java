package tora.parser.tree;


import tora.parser.Tokenizer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

public class ClassNode extends Node {
    /*Code segments taken from babel.js*/
    private static final String CREATE_CLASS = "var _createClass = function () { " +
        "function defineProperties(target, props) { for (var i = 0; i < props.length; i++) " +
        "{ var descriptor = props[i]; descriptor.enumerable = descriptor.enumerable || false; " +
        "descriptor.configurable = true; if (\"value\" in descriptor) descriptor.writable = true; " +
        "Object.defineProperty(target, descriptor.key, descriptor); } } " +
        "return function (Constructor, protoProps, staticProps) { if (protoProps) " +
        "defineProperties(Constructor.prototype, protoProps); if (staticProps) " +
        "defineProperties(Constructor, staticProps); return Constructor; }; }();\n";

    private static final String CLASS_CALL_CHECK = "function _classCallCheck(instance, Constructor) { " +
        "if (!(instance instanceof Constructor)) { " +
        "throw new TypeError(\"Cannot call a class as a function\") } }\n";

    public ClassNode(String name ) {
        super(name);
        _errorList = new LinkedList<>();
    }

    private List<Error> _errorList;

    public void addError(Error error) {
        _errorList.add(error);
    }

    public int errorCount() {
        return _errorList.size();
    }

    @Override
    public String genCode() {
        String code = CLASS_CALL_CHECK; //Makes sure constructor is called correctly
        if (!getChildren(PropertyNode.class).isEmpty()) code += CREATE_CLASS; //Defines getters and setters

        code += "var " + getName() + " = function() { ";

        if (getChildren(ConstructorNode.class).isEmpty()) {
            //Gen default constructor if no child found
            code += "\n\t" + new ConstructorNode(getName() ).genCode();
        } else code += "\n\t" + getChildren(ConstructorNode.class).get(0).genCode();

        for (FunctionNode node : getChildren(FunctionNode.class)) {
            if (node.getClass().equals(FunctionNode.class)) code += "\n\t" + node.genCode();
        }

        code += genPropertyObjectCode(getChildren(PropertyNode.class));

        code += "\n\treturn " + getName() + ";\n}();";

        return code;
    }

    private String genPropertyObjectCode (List<PropertyNode> propertyNodes) {
        //Wrapper to hold getters and setters for the same property
        class PropertyNodeWrapper {
            private String _name;
            private boolean _isStatic;
            private PropertyNode _getter = null;
            private PropertyNode _setter = null;

            public PropertyNodeWrapper(String name) {
                _name = name;
            }

            public void add(PropertyNode node) {
                if (node.isSetter()) _setter = node;
                else _getter = node;
            }

            public String genCode() {
                return "\n\t\t" + "{key: \"" + _name + "\"," +
                        (_setter != null?_setter.genCode()+",":"") +
                        (_getter != null?_getter.genCode():"") +
                        "}";
            }
        }

        String propCode = "";
        //combines getters and setters for each property
        if (!propertyNodes.isEmpty()) {
            //Separate static and non-static properties
            HashMap<String, PropertyNodeWrapper> propertyNodeBucket = new HashMap();
            HashMap<String, PropertyNodeWrapper> staticPropertyNodeBucket = new HashMap();
            propCode += "\n\t_createClass(" + getName() + ", ";
            for (PropertyNode node : propertyNodes) {
                //Get wrapper by property name, and insert name
                PropertyNodeWrapper wrapper;
                if (node.isStatic()) {
                    wrapper = staticPropertyNodeBucket.get(node.getName());
                    if (wrapper == null) wrapper = new PropertyNodeWrapper(node.getName());
                    staticPropertyNodeBucket.put(node.getName(), wrapper);
                }
                else  {
                    wrapper = propertyNodeBucket.get(node.getName());
                    if (wrapper == null) wrapper = new PropertyNodeWrapper(node.getName());
                    propertyNodeBucket.put(node.getName(), wrapper);
                }
                wrapper.add(node);
            }

            //Combine the properties into an array
            String nonStaticProps = (propertyNodeBucket.isEmpty()) ? "null" :
                    "[" + String.join(",", propertyNodeBucket.values().stream()
                        .map(prop->prop.genCode())
                        .collect(Collectors.toList())) + "]";
            String staticProps = (staticPropertyNodeBucket.isEmpty()) ? "null" :
                    "[" + String.join(",", staticPropertyNodeBucket.values().stream()
                            .map(prop->prop.genCode())
                            .collect(Collectors.toList())) + "]";
            propCode += nonStaticProps + "," + staticProps + ");";
        }
        return  propCode;
    }
}
