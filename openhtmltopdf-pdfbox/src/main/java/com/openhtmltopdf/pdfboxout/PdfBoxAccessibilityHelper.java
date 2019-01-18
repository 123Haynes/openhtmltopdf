package com.openhtmltopdf.pdfboxout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDNumberTreeNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDArtifactMarkedContent;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.StandardStructureTypes;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import com.openhtmltopdf.extend.StructureType;
import com.openhtmltopdf.render.BlockBox;
import com.openhtmltopdf.render.Box;
import com.openhtmltopdf.render.LineBox;
import com.openhtmltopdf.render.RenderingContext;

public class PdfBoxAccessibilityHelper {
    private final List<List<StructureItem>> _pageContentItems = new ArrayList<>();
    private final PdfBoxFastOutputDevice _od;
    
    private Document _doc;
    private StructureItem _root;

    private int _nextMcid;
    private PdfContentStreamAdapter _cs;
    private RenderingContext _ctx;
    private PDPage _page;
    
    public PdfBoxAccessibilityHelper(PdfBoxFastOutputDevice od) {
        this._od = od;
    }

    private static class StructureItem {
        private final StructureType type;
        private Box box;
        private final List<StructureItem> children = new ArrayList<>();
        
        private COSDictionary dict;
        private PDStructureElement elem;
        private PDStructureElement parentElem;
        private int mcid = -1;
        private StructureItem parent;
        private PDPage page;
        
        private StructureItem(StructureType type, Box box) {
            this.type = type;
            this.box = box;
        }
        
        @Override
        public String toString() {
            return box != null ? box.toString() : "null";
        }
    }
 
    public void finishPdfUa() {
        PDStructureTreeRoot root = _od.getWriter().getDocumentCatalog().getStructureTreeRoot();
        if (root == null) {
            root = new PDStructureTreeRoot();
            
            HashMap<String, String> roleMap = new HashMap<>();
            roleMap.put("Annotation", "Span");
            roleMap.put("Artifact", "P");
            roleMap.put("Bibliography", "BibEntry");
            roleMap.put("Chart", "Figure");
            roleMap.put("Diagram", "Figure");
            roleMap.put("DropCap", "Figure");
            roleMap.put("EndNote", "Note");
            roleMap.put("FootNote", "Note");
            roleMap.put("InlineShape", "Figure");
            roleMap.put("Outline", "Span");
            roleMap.put("Strikeout", "Span");
            roleMap.put("Subscript", "Span");
            roleMap.put("Superscript", "Span");
            roleMap.put("Underline", "Span");
            root.setRoleMap(roleMap);

            PDStructureElement rootElem = new PDStructureElement(StandardStructureTypes.DOCUMENT, null);
            
            String lang = _doc.getDocumentElement().getAttribute("lang");
            rootElem.setLanguage(lang.isEmpty() ? "EN-US" : lang);
            
            root.appendKid(rootElem);
            
            _root.elem = rootElem;
            finishStructure(_root, _root.elem);
            
            _od.getWriter().getDocumentCatalog().setStructureTreeRoot(root);
        }
        
        COSArray numTree = new COSArray();
        
        for (int i = 0; i < _pageContentItems.size(); i++) {
            PDPage page = _od.getWriter().getPage(i);
            List<StructureItem> pageItems = _pageContentItems.get(i);
            
            COSArray mcidParentReferences = new COSArray();
            for (StructureItem item : pageItems) {
System.out.println("%%%%%%%item = " + item + ", parent = " + item.parentElem);
                mcidParentReferences.add(item.parentElem);
            }
        
            numTree.add(COSInteger.get(i));
            numTree.add(mcidParentReferences);
            
            page.getCOSObject().setItem(COSName.STRUCT_PARENTS, COSInteger.get(i));
        }
        
        COSDictionary dict = new COSDictionary();
        dict.setItem(COSName.NUMS, numTree);
    
        PDNumberTreeNode numberTreeNode = new PDNumberTreeNode(dict, dict.getClass());
        _od.getWriter().getDocumentCatalog().getStructureTreeRoot().setParentTreeNextKey(_pageContentItems.size());
        _od.getWriter().getDocumentCatalog().getStructureTreeRoot().setParentTree(numberTreeNode);
    }
        
    private String chooseTag(StructureItem item) {
        if (item.box != null) {
            if (item.box.getLayer() != null) {
                return StandardStructureTypes.SECT;
            } else if (item.box instanceof BlockBox) {
                BlockBox block = (BlockBox) item.box;
                
                if (block.isFloated()) {
                    return StandardStructureTypes.NOTE;
                } else if (block.isInline()) {
                    return StandardStructureTypes.SPAN;
                } else if (block.getElement() != null && block.getElement().getNodeName().equals("p")) {
                    return StandardStructureTypes.P;
                } else {
                    return StandardStructureTypes.DIV;
                }
                
                // TODO: Tables.
            } else {
                return StandardStructureTypes.SPAN;
            }
        }
        
        return StandardStructureTypes.SPAN;
    }
    
    private void finishStructure(StructureItem item, PDStructureElement parent) {
        for (StructureItem child : item.children) {
            if (child.mcid == -1) {
                if (child.children.isEmpty()) {
                    continue;
                }
                
                if (child.box instanceof LineBox &&
                    !child.box.hasNonTextContent(_ctx)) {
                    finishStructure(child, parent);
                } else {
                    String pdfTag = chooseTag(child);
                
                    child.parentElem = parent;
                    child.elem = new PDStructureElement(pdfTag, parent);
                    child.elem.setParent(parent);
                    child.elem.setPage(child.page);
System.out.println("ADDING$$: " + child + " :::: " + child.elem + "-----" + pdfTag);
                    parent.appendKid(child.elem);
                    
                    finishStructure(child, child.elem);
                }
            } else if (child.type == StructureType.TEXT) {
                child.parentElem = parent;
                parent.appendKid(new PDMarkedContent(COSName.getPDFName("Span"), child.dict));
            } else if (child.type == StructureType.BACKGROUND) {
                child.parentElem = parent;
                parent.appendKid(new PDArtifactMarkedContent(child.dict));
            }
        }
    }
    
    private Element getBoxElement(Box box) {
        if (box.getElement() != null) {
            return box.getElement();
        } else if (box.getParent() != null) {
            return getBoxElement(box.getParent());
        } else {
            return _doc.getDocumentElement();
        }
    }
    
    private COSDictionary createMarkedContentDictionary() {
        COSDictionary dict = new COSDictionary();
        dict.setInt(COSName.MCID, _nextMcid);
        _nextMcid++;
        return dict;
    }
    
    private void ensureAncestorTree(StructureItem child, Box parent) {
        // Walk up the ancestor tree making sure they all have accessibility objects.
        while (parent != null && parent.getAccessibilityObject() == null) {
            StructureItem parentItem = createStructureItem(null, parent);
            parent.setAccessiblityObject(parentItem);
            parentItem.children.add(child);
            child.parent = parentItem;
            child = parentItem;
            parent = parent.getParent();
        }
    }
    
    private StructureItem createStructureItem(StructureType type, Box box) {
        StructureItem child = (StructureItem) box.getAccessibilityObject();
        
        if (child == null) {
            child = new StructureItem(type, box);
            child.page = _page;
            
            box.setAccessiblityObject(child);
            
            ensureAncestorTree(child, box.getParent());
            ensureParent(box, child);
        } else if (child.box == null) {
            child.box = box;
        }

System.out.println("-------ADD: " + child + " && " + child.parent);
        return child;
    }

    public void ensureParent(Box box, StructureItem child) {
        if (child.parent == null) {
            if (box.getParent() != null) {
                StructureItem parent = (StructureItem) box.getParent().getAccessibilityObject();
                parent.children.add(child);
                child.parent = parent;
            } else {
                _root.children.add(child);
                child.parent = _root;
            }
        }
    }
    
    private StructureItem createMarkedContentStructureItem(StructureType type, Box box) {
        StructureItem current = new StructureItem(type, box);
        
        ensureAncestorTree(current, box.getParent());
        ensureParent(box, current);

        current.mcid = _nextMcid;
        current.dict = createMarkedContentDictionary();
        
        _pageContentItems.get(_pageContentItems.size() - 1).add(current);

System.out.println("+++++++ADD: " + current + " !! " + current.parent + " !! " + current.mcid);
        
        return current;
    }
    
    public void startStructure(StructureType type, Box box) {
            switch (type) {
            case LAYER:
            case FLOAT:
            case BLOCK: {
                createStructureItem(type, box);
                break;
            }
            case INLINE: {
                if (box.hasNonTextContent(_ctx)) {
                    createStructureItem(type, box);
                }
                break;
            }
            case BACKGROUND: {
                if (box.hasNonTextContent(_ctx)) {
                    StructureItem current = createMarkedContentStructureItem(type, box);
                    _cs.beginMarkedContent(COSName.ARTIFACT, current.dict);    
                }
                break;
            }
            case TEXT: {
                StructureItem current = createMarkedContentStructureItem(type, box);
                _cs.beginMarkedContent(COSName.getPDFName(StandardStructureTypes.SPAN), current.dict);
                break;
            }
            default:
                break;
            }
    }

    public void endStructure(StructureType type, Box box) {
            switch (type) {
            case LAYER:
            case FLOAT:
            case BLOCK: {
                break;
            }
            case INLINE: {
                break;
            }
            case BACKGROUND: {
                if (box.hasNonTextContent(_ctx)) {
                    _cs.endMarkedContent();
                }
                break;
            }
            case TEXT: {
                _cs.endMarkedContent();
                break;
            }
            default:
                break;
            }
    }

    public void startPage(PDPage page, PdfContentStreamAdapter cs, RenderingContext ctx) {
        this._cs = cs;
        this._ctx = ctx;
        this._nextMcid = 0;
        this._page = page;
        this._pageContentItems.add(new ArrayList<>());
    }
    
    public void endPage() {
        
    }

    public void setDocument(Document doc) {
        this._doc = doc;
        
        StructureItem rootStruct = new StructureItem(null, null);
        _root = rootStruct;
    }
    
}
