package com.openhtmltopdf.render.displaylist;

import java.util.List;
import java.util.Map;

import com.openhtmltopdf.layout.CollapsedBorderSide;
import com.openhtmltopdf.layout.InlinePaintable;
import com.openhtmltopdf.newtable.TableCellBox;
import com.openhtmltopdf.render.BlockBox;
import com.openhtmltopdf.render.DisplayListItem;
import com.openhtmltopdf.render.OperatorClip;
import com.openhtmltopdf.render.OperatorSetClip;
import com.openhtmltopdf.render.RenderingContext;

public class DisplayListPainter {
	private void dlPaintBackgroundAndBorders(RenderingContext c, List<DisplayListItem> blocks,
			Map<TableCellBox, List<CollapsedBorderSide>> collapsedTableBorders) {
		for (DisplayListItem dli : blocks) {
			if (dli instanceof OperatorClip) {
				OperatorClip clip = (OperatorClip) dli;
				c.getOutputDevice().clip(clip.getClip());
			} else if (dli instanceof OperatorSetClip) {
				OperatorSetClip setClip = (OperatorSetClip) dli;
				c.getOutputDevice().setClip(setClip.getSetClipShape());
			} else {
				BlockBox box = (BlockBox) dli;

				box.paintBackground(c);
				box.paintBorder(c);

				if (collapsedTableBorders != null && box instanceof TableCellBox) {
					TableCellBox cell = (TableCellBox) box;

					if (cell.hasCollapsedPaintingBorder()) {
						List<CollapsedBorderSide> borders = collapsedTableBorders.get(cell);

						if (borders != null) {
							for (CollapsedBorderSide border : borders) {
								border.getCell().paintCollapsedBorder(c, border.getSide());
							}
						}
					}
				}
			}
		}
	}

	private void dlPaintListMarkers(RenderingContext c, List<DisplayListItem> blocks) {
		for (DisplayListItem dli : blocks) {
			if (dli instanceof OperatorClip) {
				OperatorClip clip = (OperatorClip) dli;
				c.getOutputDevice().clip(clip.getClip());
			} else if (dli instanceof OperatorSetClip) {
				OperatorSetClip setClip = (OperatorSetClip) dli;
				c.getOutputDevice().setClip(setClip.getSetClipShape());
			} else {
				((BlockBox) dli).paintListMarker(c);
			}
		}
	}

	private void dlPaintInlineContent(RenderingContext c, List<DisplayListItem> inlines) {
		for (DisplayListItem dli : inlines) {
			if (dli instanceof OperatorClip) {
				OperatorClip clip = (OperatorClip) dli;
				c.getOutputDevice().clip(clip.getClip());
			} else if (dli instanceof OperatorSetClip) {
				OperatorSetClip setClip = (OperatorSetClip) dli;
				c.getOutputDevice().setClip(setClip.getSetClipShape());
			} else {
				InlinePaintable paintable = (InlinePaintable) dli;
				paintable.paintInline(c);
			}
		}
	}

	private void dlPaintReplacedElements(RenderingContext c, List<DisplayListItem> replaceds) {
		for (int i = 0; i < replaceds.size(); i++) {
			DisplayListItem dli = replaceds.get(i);
			DisplayListItem prev = (i - 1) >= 0 ? replaceds.get(i - 1) : null;
			DisplayListItem next = (i + 1) < replaceds.size() ? replaceds.get(i + 1) : null;

			if (dli instanceof OperatorClip) {
				if (next instanceof OperatorSetClip) {
					// Its an empty clip/setClip pair with no replaceds between them.
					continue;
				}

				OperatorClip clip = (OperatorClip) dli;
				c.getOutputDevice().clip(clip.getClip());
			} else if (dli instanceof OperatorSetClip) {
				if (prev instanceof OperatorClip) {
					// Its an empty clip/setClip pair with no replaceds between them.
					continue;
				}

				OperatorSetClip setClip = (OperatorSetClip) dli;
				c.getOutputDevice().setClip(setClip.getSetClipShape());
			} else {
				BlockBox box = (BlockBox) dli;
				c.getOutputDevice().paintReplacedElement(c, box);
			}
		}
	}

	public void dlPaint(RenderingContext c, List<DisplayListOperation> pageOperations) {
		for (DisplayListOperation op : pageOperations) {

			if (op instanceof PaintRootElementBackground) {

				PaintRootElementBackground dlo = (PaintRootElementBackground) op;
				dlo.getRoot().paintRootElementBackground(dlo.getContext());

			} else if (op instanceof PaintLayerBackgroundAndBorder) {

				PaintLayerBackgroundAndBorder dlo = (PaintLayerBackgroundAndBorder) op;
				dlo.getMaster().paintBackground(dlo.getContext());
				dlo.getMaster().paintBorder(dlo.getContext());

			} else if (op instanceof PaintReplacedElement) {

				PaintReplacedElement dlo = (PaintReplacedElement) op;
				dlo.getContext().getOutputDevice().paintReplacedElement(dlo.getContext(), dlo.getMaster());

			} else if (op instanceof PaintBackgroundAndBorders) {

				PaintBackgroundAndBorders dlo = (PaintBackgroundAndBorders) op;
				dlPaintBackgroundAndBorders(dlo.getContext(), dlo.getBlocks(), dlo.getCollapedTableBorders());

			} else if (op instanceof PaintListMarkers) {

				PaintListMarkers dlo = (PaintListMarkers) op;
				dlPaintListMarkers(dlo.getContext(), dlo.getBlocks());

			} else if (op instanceof PaintInlineContent) {

				PaintInlineContent dlo = (PaintInlineContent) op;
				dlPaintInlineContent(dlo.getContext(), dlo.getInlines());

			} else if (op instanceof PaintReplacedElements) {

				PaintReplacedElements dlo = (PaintReplacedElements) op;
				dlPaintReplacedElements(dlo.getContext(), dlo.getReplaceds());

			} else {

			}
		}
	}

}
