package jada.ngeditor.model.utils;

import de.lessvoid.nifty.Nifty;
import de.lessvoid.nifty.builder.PopupBuilder;
import de.lessvoid.nifty.elements.Element;
import de.lessvoid.nifty.tools.SizeValue;
import jada.ngeditor.model.elements.GElement;

/**
 * SDK replacement for the legacy binary's drag helper. Compile the popup builder
 * calls against the installed Nifty API. All calls run on the Swing preview EDT.
 * Cancel restores constraints, including percentage sizes, after the move ends.
 */
public class NiftyDDManager {
    public static final String NIFTY_EDITOR_POPUP_SUPPORT = "NiftyEditorPopupSupport";
    private final Nifty nifty;
    private final Element popUp;
    private GElement dragged;
    private int previousX;
    private int previousY;
    private int previousIndex;
    private SizeValue x;
    private SizeValue y;
    private SizeValue width;
    private SizeValue height;

    public NiftyDDManager(Nifty nifty) {
        this.nifty = nifty;
        Element popup = nifty.findPopupByName(NIFTY_EDITOR_POPUP_SUPPORT);
        if (popup == null) {
            PopupBuilder builder = new PopupBuilder(NIFTY_EDITOR_POPUP_SUPPORT);
            builder.childLayoutAbsolute();
            builder.registerPopup(nifty);
            popup = nifty.createPopup(NIFTY_EDITOR_POPUP_SUPPORT);
        }
        popUp = popup;
    }

    public void startDrag(GElement element) {
        if (dragged != null) {
            throw new IllegalStateException("A drag is already active");
        }
        Element target = element.getNiftyElement();
        previousX = target.getX();
        previousY = target.getY();
        previousIndex = element.getParent().getElements().indexOf(element);
        x = target.getConstraintX();
        y = target.getConstraintY();
        width = target.getConstraintWidth();
        height = target.getConstraintHeight();
        dragged = element;
        nifty.showPopup(nifty.getCurrentScreen(), popUp.getId(), null);
        target.setConstraintX(SizeValue.px(previousX));
        target.setConstraintY(SizeValue.px(previousY));
        target.setConstraintWidth(SizeValue.px(target.getWidth()));
        target.setConstraintHeight(SizeValue.px(target.getHeight()));
        target.markForMove(popUp);
    }

    public void dragAround(int mouseX, int mouseY) {
        if (dragged == null) {
            return;
        }
        Element target = dragged.getNiftyElement();
        target.setConstraintX(SizeValue.px(mouseX - target.getWidth() / 2));
        target.setConstraintY(SizeValue.px(mouseY - target.getHeight() / 2));
        popUp.layoutElements();
    }

    public void endDrag() {
        nifty.closePopup(popUp.getId());
        dragged = null;
    }

    public void revertDrag() {
        if (dragged == null) {
            return;
        }
        final GElement original = dragged;
        final Element target = original.getNiftyElement();
        final SizeValue originalX = x;
        final SizeValue originalY = y;
        final SizeValue originalWidth = width;
        final SizeValue originalHeight = height;
        final int index = previousIndex;
        target.markForMove(original.getParent().getDropContext(), () -> {
            target.setConstraintX(originalX);
            target.setConstraintY(originalY);
            target.setConstraintWidth(originalWidth);
            target.setConstraintHeight(originalHeight);
            target.setIndex(index);
            original.refresh();
            nifty.closePopup(popUp.getId());
            dragged = null;
        });
    }

    public ElementState getElementState() {
        return new ElementState(previousX, previousY, previousIndex);
    }

    public static class ElementState {
        private final int x;
        private final int y;
        private final int index;

        public ElementState(int x, int y, int index) {
            this.x = x;
            this.y = y;
            this.index = index;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getIndex() {
            return index;
        }
    }
}
