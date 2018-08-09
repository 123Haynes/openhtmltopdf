package com.openhtmltopdf.render.displaylist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.openhtmltopdf.layout.CollapsedBorderSide;
import com.openhtmltopdf.layout.Layer;
import com.openhtmltopdf.newtable.CollapsedBorderValue;
import com.openhtmltopdf.newtable.TableBox;
import com.openhtmltopdf.newtable.TableCellBox;
import com.openhtmltopdf.render.BlockBox;
import com.openhtmltopdf.render.PageBox;
import com.openhtmltopdf.render.RenderingContext;
import com.openhtmltopdf.render.displaylist.DisplayListContainer.DisplayListPageContainer;
import com.openhtmltopdf.render.displaylist.PagedBoxCollector.PageResult;

public class DisplayListCollector {
    protected static enum CollectFlags {
        /**
         * Fixed layers appear on each page. To avoid having to clone each box in a fixed layer onto 
         * each page, we have this flag so we can exclude fixed boxes in the multi page run and just
         * collect them at the last minute when painting a particular page. 
         */
        INCLUDE_FIXED_BOXES;
    }
    
 	private final List<PageBox> _pages;
	
	public DisplayListCollector(List<PageBox> pages) {
		this._pages = pages;
	}

	private void collectLayers(RenderingContext c, List<Layer> layers, DisplayListContainer dlPages, Set<CollectFlags> flags) {
		for (Layer layer : layers) {
			collect(c, layer, dlPages, flags);
		}
	}

	/**
	 * Adds a paint operation to a selection of pages, from pgStart to pgEnd inclusive.
	 */
	protected void addItem(DisplayListOperation item, int pgStart, int pgEnd,
			DisplayListContainer dlPages) {
		for (int i = pgStart; i <= pgEnd; i++) {
			dlPages.getPageInstructions(i).addOp(item);
		}
	}

	/**
	 * Use this method to collect all boxes recursively into a list of paint instructions
	 * for each page.
	 */
	public DisplayListContainer collectRoot(RenderingContext c, Layer rootLayer) {
		if (!rootLayer.isRootLayer()) {
			return null;
		}
		
		// We propagate any transformation matrixes recursively after layout has finished.
		rootLayer.propagateCurrentTransformationMatrix(c);

		DisplayListContainer displayList = new DisplayListContainer(0, _pages.size());

		// Recursively collect boxes for root layer and any children layers. Don't include
		// fixed boxes at this point. They are collected by the <code>SinglePageDisplayListCollector</code>
		// at the point of painting each page.
		collect(c, rootLayer, displayList, EnumSet.noneOf(CollectFlags.class));

		return displayList;
	}

	/** 
	 * The main method to create a list of paint instruction for each page.
	 */
	protected void collect(RenderingContext c, Layer layer, DisplayListContainer dlPages, Set<CollectFlags> flags) {
		if (layer.getMaster().getStyle().isFixed() && !flags.contains(CollectFlags.INCLUDE_FIXED_BOXES)) {
			// We don't collect fixed layers or their children here, because we don't want to have
			// to clone the entire subtree of the fixed box and all descendents.
			// So just paint it at the last minute.
			DisplayListOperation dlo = new PaintFixedLayer(layer);
			addItem(dlo, 0, _pages.size() - 1, dlPages);
			return;
		}
		
		int layerPageStart = findStartPage(c, layer);
		int layerPageEnd = findEndPage(c, layer);

	    if (layer.hasLocalTransform()) {
	        DisplayListOperation dlo = new PaintPushTransformLayer(layer.getMaster());
	        addItem(dlo, layerPageStart, layerPageEnd, dlPages);
	    }
		
		if (!layer.getMaster().getStyle().isPositioned() &&
			!layer.getClipBoxes().isEmpty()) {
			// This layer was triggered by a transform. We have to honor the clip of parent elements.
			DisplayListOperation  dlo = new PaintPushClipLayer(layer.getClipBoxes());
			addItem(dlo, layerPageStart, layerPageEnd, dlPages);
		} else {
			// This layer was triggered by a positioned element. We should honor the clip of the
			// containing block (closest ancestor with position other than static) and its containing block and
			// so on.
			// TODO
		}

		if (layer.isRootLayer() && layer.getMaster().hasRootElementBackground(c)) {

			// IMPROVEMENT: If the background image doesn't cover every page,
			// we could perhaps optimize this.
			DisplayListOperation dlo = new PaintRootElementBackground(layer.getMaster());
			addItem(dlo, dlPages.getMinPage(), dlPages.getMaxPage(), dlPages);
		}
		
		if (!layer.isInline() && ((BlockBox) layer.getMaster()).isReplaced()) {
			collectReplacedElementLayer(c, layer, dlPages, layerPageStart, layerPageEnd);
		} else {

			PagedBoxCollector collector = createBoundedBoxCollector(layerPageStart, layerPageEnd);
			
			collector.collectFloats(c, layer);
			collector.collect(c, layer);

			if (!layer.isInline() && layer.getMaster() instanceof BlockBox) {
				collectLayerBackgroundAndBorder(c, layer, dlPages, layerPageStart, layerPageEnd);
			}

			if (layer.isRootLayer() || layer.isStackingContext()) {
				collectLayers(c, layer.getSortedLayers(Layer.NEGATIVE), dlPages, flags);
			}

			for (int pageNumber = layerPageStart; pageNumber <= layerPageEnd; pageNumber++) {
				PageResult pg = collector.getPageResult(pageNumber);
				DisplayListPageContainer dlPageList = dlPages.getPageInstructions(pageNumber);

				processPage(c, layer, pg, dlPageList, true, pageNumber);
				
				int shadowCnt = 0;
			    for (PageResult shadow : pg.shadowPages()) {
			        DisplayListPageContainer shadowPage = dlPageList.getShadowPage(shadowCnt);
			        
				    processPage(c, layer, shadow, shadowPage, true, pageNumber);
				    
				    shadowCnt++;
			    }
			}

			if (layer.isRootLayer() || layer.isStackingContext()) {
				collectLayers(c, layer.collectLayers(Layer.AUTO), dlPages, flags);
				// TODO z-index: 0 layers should be painted atomically
				collectLayers(c, layer.getSortedLayers(Layer.ZERO), dlPages, flags);
				collectLayers(c, layer.getSortedLayers(Layer.POSITIVE), dlPages, flags);
			}
		}
		
		if (layer.hasLocalTransform()) {
			DisplayListOperation dlo = new PaintPopTransformLayer(layer.getMaster());
			addItem(dlo, layerPageStart, layerPageEnd, dlPages);
		}
		
		if (!layer.getMaster().getStyle().isPositioned() &&
			!layer.getClipBoxes().isEmpty()) {
			DisplayListOperation dlo = new PaintPopClipLayer(layer.getClipBoxes());
			addItem(dlo, layerPageStart, layerPageEnd, dlPages);
		}
	}

    protected void processPage(RenderingContext c, Layer layer, PageResult pg, DisplayListPageContainer dlPageList, boolean includeFloats, int pageNumber) {

        if (!pg.blocks().isEmpty()) {
            Map<TableCellBox, List<CollapsedBorderSide>> collapsedTableBorders = pg.tcells().isEmpty() ? null
                    : collectCollapsedTableBorders(c, pg.tcells());
            DisplayListOperation dlo = new PaintBackgroundAndBorders(pg.blocks(), collapsedTableBorders);
            dlPageList.addOp(dlo);
        }
        
        if (includeFloats) {
            for (BlockBox floater : pg.floats()) {
                collectFloatAsLayer(c, layer, floater, dlPageList, pageNumber);
            }
        }

        if (!pg.listItems().isEmpty()) {
            DisplayListOperation dlo = new PaintListMarkers(pg.listItems());
            dlPageList.addOp(dlo);
        }

        if (!pg.inlines().isEmpty()) {
            DisplayListOperation dlo = new PaintInlineContent(pg.inlines());
            dlPageList.addOp(dlo);
        }

        if (!pg.replaceds().isEmpty()) {
            DisplayListOperation dlo = new PaintReplacedElements(pg.replaceds());
            dlPageList.addOp(dlo);
        }
    }
	
	private void collectFloatAsLayer(RenderingContext c, Layer layer, BlockBox floater, DisplayListPageContainer pageInstructions, int pageNumber) {
	    SinglePageBoxCollector collector = new SinglePageBoxCollector(pageNumber, _pages.get(pageNumber));

		collector.collect(c, layer, floater, pageNumber, pageNumber);

		PageResult pageBoxes = collector.getPageResult(pageNumber);

		processPage(c, layer, pageBoxes, pageInstructions, false, pageNumber);
	}

	private void collectLayerBackgroundAndBorder(RenderingContext c, Layer layer,
			DisplayListContainer dlPages, int pgStart, int pgEnd) {

		DisplayListOperation dlo = new PaintLayerBackgroundAndBorder(layer.getMaster());
		addItem(dlo, pgStart, pgEnd, dlPages);
	}

	private void collectReplacedElementLayer(RenderingContext c, Layer layer,
			DisplayListContainer dlPages, int pgStart, int pgEnd) {

		DisplayListOperation dlo = new PaintLayerBackgroundAndBorder(layer.getMaster());
		addItem(dlo, pgStart, pgEnd, dlPages);

		DisplayListOperation dlo2 = new PaintReplacedElement((BlockBox) layer.getMaster());
		addItem(dlo2, pgStart, pgEnd, dlPages);
	}

	// Bit of a kludge here. We need to paint collapsed table borders according
	// to priority so (for example) wider borders float to the top and aren't
	// overpainted by thinner borders. This method takes the table cell boxes
	// (only those with collapsed border painting)
	// we're about to draw and returns a map with the last cell in a given table
	// we'll paint as a key and a sorted list of borders as values. These are
	// then painted after we've drawn the background for this cell.
	private Map<TableCellBox, List<CollapsedBorderSide>> collectCollapsedTableBorders(RenderingContext c,
			List<TableCellBox> tcells) {
		Map<TableBox, List<CollapsedBorderSide>> cellBordersByTable = new HashMap<TableBox, List<CollapsedBorderSide>>();
		Map<TableBox, TableCellBox> triggerCellsByTable = new HashMap<TableBox, TableCellBox>();

		Set<CollapsedBorderValue> all = new HashSet<CollapsedBorderValue>(0);

		for (TableCellBox cell : tcells) {
			List<CollapsedBorderSide> borders = cellBordersByTable.get(cell.getTable());

			if (borders == null) {
				borders = new ArrayList<CollapsedBorderSide>();
				cellBordersByTable.put(cell.getTable(), borders);
			}

			triggerCellsByTable.put(cell.getTable(), cell);
			cell.addCollapsedBorders(all, borders);
		}

		if (triggerCellsByTable.isEmpty()) {
			return null;
		} else {
			Map<TableCellBox, List<CollapsedBorderSide>> result = new HashMap<TableCellBox, List<CollapsedBorderSide>>(
					triggerCellsByTable.size());

			for (TableCellBox cell : triggerCellsByTable.values()) {
				List<CollapsedBorderSide> borders = cellBordersByTable.get(cell.getTable());
				Collections.sort(borders);
				result.put(cell, borders);
			}

			return result;
		}
	}
	
	protected PagedBoxCollector createBoundedBoxCollector(int pgStart, int pgEnd) {
	    return new PagedBoxCollector(_pages, pgStart, pgEnd);
	}
	
	protected int findStartPage(RenderingContext c, Layer layer) {
	    int start = PagedBoxCollector.findStartPage(c, layer.getMaster(), _pages);
	    
	    // Floats maybe outside the master box.
	    for (BlockBox floater : layer.getFloats()) {
	        start = Math.min(start, PagedBoxCollector.findStartPage(c, floater, _pages));
	    }
	    
	    return start;
	}
	
	protected int findEndPage(RenderingContext c, Layer layer) {
	    int end = PagedBoxCollector.findEndPage(c, layer.getMaster(), _pages);
	    
	    // Floats may be outside the master box.
	    for (BlockBox floater : layer.getFloats()) {
	        end = Math.max(end, PagedBoxCollector.findEndPage(c, floater, _pages));
	    }
	    
	    return end;
	}
}
