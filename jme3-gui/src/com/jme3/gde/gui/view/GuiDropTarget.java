package com.jme3.gde.gui.view;

import java.awt.Point;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.geom.Point2D;
import java.util.function.Consumer;
import jada.ngeditor.controller.CommandProcessor;
import jada.ngeditor.controller.commands.AddElementCommand;
import jada.ngeditor.controller.commands.MoveCommand;
import jada.ngeditor.guiviews.DND.WidgetData;
import jada.ngeditor.guiviews.J2DNiftyView;
import jada.ngeditor.model.elements.GElement;

/**
 * Accepts palette copies and preview moves explicitly during AWT negotiation.
 * The owner is activated before commands are created, including when the palette
 * has keyboard focus. Coordinates are relative to the view, not its viewport.
 * All callbacks execute on the EDT; command processing retains undo support.
 */
final class GuiDropTarget extends DropTarget {
    private final J2DNiftyView view;
    private final Runnable activate;
    private final Runnable changed;
    private final Consumer<Exception> failure;

    GuiDropTarget(J2DNiftyView view, Runnable activate, Runnable changed,
            Consumer<Exception> failure) {
        this.view = view;
        this.activate = activate;
        this.changed = changed;
        this.failure = failure;
    }

    /**
     * NetBeans Palette advertises MOVE only. Its transport action must not be
     * confused with an internal move: only POINTFLAVOR selects MoveCommand.
     * Palette drops always create a fresh component through AddElementCommand.
     */
    static int transportAction(int sourceActions, boolean internalMove) {
        if (!internalMove && (sourceActions & DnDConstants.ACTION_COPY) != 0) {
            return DnDConstants.ACTION_COPY;
        }
        if ((sourceActions & DnDConstants.ACTION_MOVE) != 0) {
            return DnDConstants.ACTION_MOVE;
        }
        return DnDConstants.ACTION_NONE;
    }

    private void accept(DropTargetDragEvent event) {
        if (!event.isDataFlavorSupported(WidgetData.FLAVOR)) {
            event.rejectDrag();
            return;
        }
        int action = transportAction(event.getSourceActions(), event.isDataFlavorSupported(WidgetData.POINTFLAVOR));
        if (action == DnDConstants.ACTION_NONE) {
            event.rejectDrag();
            return;
        }
        event.acceptDrag(action);
    }

    @Override
    public void dragEnter(DropTargetDragEvent event) {
        accept(event);
    }

    @Override
    public void dropActionChanged(DropTargetDragEvent event) {
        accept(event);
    }

    @Override
    public void dragOver(DropTargetDragEvent event) {
        accept(event);
        if (event.isDataFlavorSupported(WidgetData.POINTFLAVOR)) {
            Point point = event.getLocation();
            view.getDDManager().dragAround(point.x, point.y);
        }
    }

    @Override
    public void drop(DropTargetDropEvent event) {
        if (!event.isDataFlavorSupported(WidgetData.FLAVOR)) {
            event.rejectDrop();
            return;
        }
        boolean move = event.isDataFlavorSupported(WidgetData.POINTFLAVOR);
        int action = transportAction(event.getSourceActions(), move);
        if (action == DnDConstants.ACTION_NONE) {
            event.rejectDrop();
            return;
        }
        event.acceptDrop(action);
        boolean success = false;
        try {
            activate.run();
            GElement element = (GElement) event.getTransferable().getTransferData(WidgetData.FLAVOR);
            Point point = event.getLocation();
            CommandProcessor processor = CommandProcessor.getInstance();
            if (move) {
                Point2D offset = (Point2D) event.getTransferable().getTransferData(WidgetData.POINTFLAVOR);
                MoveCommand command = processor.getCommand(MoveCommand.class);
                command.setElement(element);
                command.setTo(new Point2D.Double(point.x - offset.getX(), point.y - offset.getY()));
                command.setElementState(view.getDDManager().getElementState());
                processor.excuteCommand(command);
                view.getDDManager().endDrag();
            } else {
                AddElementCommand command = processor.getCommand(AddElementCommand.class);
                command.setChild(element);
                command.setPoint(point);
                processor.excuteCommand(command);
            }
            success = true;
            changed.run();
            view.repaint();
        } catch (Exception ex) {
            if (move) {
                try {
                    view.getDDManager().revertDrag();
                } catch (RuntimeException restoreFailure) {
                    ex.addSuppressed(restoreFailure);
                }
            }
            failure.accept(ex);
        } finally {
            event.dropComplete(success);
        }
    }
}

