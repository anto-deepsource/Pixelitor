/*
 * Copyright 2022 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.layers;

import pixelitor.Composition;
import pixelitor.ConsistencyChecks;
import pixelitor.history.History;
import pixelitor.history.LayerOrderChangeEdit;
import pixelitor.history.MergeDownEdit;
import pixelitor.utils.ImageUtils;
import pixelitor.utils.debug.Debuggable;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Something that has an ordered collection of layers inside it.
 */
public interface LayerHolder extends Debuggable {
    int getActiveLayerIndex();

    int indexOf(Layer layer);

    int getNumLayers();

    Layer getLayer(int index);

    void addLayerToList(int index, Layer newLayer);

    Composition getComp();

    String getName();

    default void moveActiveLayerUp() {
        assert isHolderOfActiveLayer();

        int oldIndex = indexOf(getComp().getActiveLayer());
        changeLayerOrder(oldIndex, oldIndex + 1,
            true, LayerMoveAction.RAISE_LAYER);
    }

    default void moveActiveLayerDown() {
        assert isHolderOfActiveLayer();

        int oldIndex = indexOf(getComp().getActiveLayer());
        changeLayerOrder(oldIndex, oldIndex - 1,
            true, LayerMoveAction.LOWER_LAYER);
    }

    default void moveActiveLayerToTop() {
        assert isHolderOfActiveLayer();

        int oldIndex = indexOf(getComp().getActiveLayer());
        int newIndex = getNumLayers() - 1;
        changeLayerOrder(oldIndex, newIndex,
            true, LayerMoveAction.LAYER_TO_TOP);
    }

    default void moveActiveLayerToBottom() {
        assert isHolderOfActiveLayer();

        int oldIndex = indexOf(getComp().getActiveLayer());
        changeLayerOrder(oldIndex, 0,
            true, LayerMoveAction.LAYER_TO_BOTTOM);
    }

    default void changeLayerOrder(int oldIndex, int newIndex) {
        changeLayerOrder(oldIndex, newIndex, false, null);
    }

    // Called when the layer order is changed by an action.
    // The GUI has to be updated.
    default void changeLayerOrder(int oldIndex, int newIndex,
                                  boolean addToHistory, String editName) {
        if (newIndex < 0) {
            return;
        }
        if (newIndex >= getNumLayers()) {
            return;
        }
        if (oldIndex == newIndex) {
            return;
        }

        Layer layer = getLayer(oldIndex);
        removeLayerFromList(layer);
        insertLayer(layer, newIndex, false);

        changeLayerGUIOrder(oldIndex, newIndex);
        update();
        Layers.layerOrderChanged(this);

        if (addToHistory) {
            History.add(new LayerOrderChangeEdit(editName, this, oldIndex, newIndex));
        }
    }

    void changeLayerGUIOrder(int oldIndex, int newIndex);

    void insertLayer(Layer layer, int index, boolean update);

    void removeLayerFromList(Layer layer);

    void deleteLayer(Layer layer, boolean addToHistory);

    /**
     * This form of layer deletion allows to temporarily violate the
     * constraint that some holders must always contain at least one layer.
     */
    void deleteTemporarily(Layer layer);

    boolean allowZeroLayers();

    default boolean canDelete() {
        if (allowZeroLayers()) {
            return getNumLayers() >= 1;
        } else {
            return getNumLayers() >= 2;
        }
    }

    /**
     * Replaces a layer with another, while keeping its position, mask, ui
     */
    void replaceLayer(Layer before, Layer after);

    void update();

    default void raiseLayerSelection() {
        Composition comp = getComp();
        Layer activeLayer = comp.getActiveLayer();
        Layer newTarget;

        int oldIndex = indexOf(activeLayer);

        int newIndex = oldIndex + 1;
        if (newIndex >= getNumLayers()) {
            if (activeLayer.isTopLevel()) {
                return;
            } else {
                // if the top layer is selected, then
                // raise selection selects the holder itself
                // select the target's parent holder
                assert activeLayer.getHolder() == this;
                newTarget = (CompositeLayer) this;
            }
        } else {
            newTarget = getLayer(newIndex);
        }

        comp.setActiveLayer(newTarget, true,
            LayerMoveAction.RAISE_LAYER_SELECTION);

        assert ConsistencyChecks.fadeWouldWorkOn(comp);
    }

    default void lowerLayerSelection() {
        Composition comp = getComp();
        int oldIndex = indexOf(comp.getActiveLayer());
        int newIndex = oldIndex - 1;
        if (newIndex < 0) {
            return;
        }

        comp.setActiveLayer(getLayer(newIndex), true,
            LayerMoveAction.LOWER_LAYER_SELECTION);

        assert ConsistencyChecks.fadeWouldWorkOn(comp);
    }

    default boolean canMergeDown(Layer layer) {
        int index = indexOf(layer);
        if (index > 0 && layer.isVisible()) {
            Layer bellow = getLayer(index - 1);
            // An instanceof check would be incorrect, because it should not merge on smart objects
            return bellow.getClass() == ImageLayer.class && bellow.isVisible();
        }
        return false;
    }

    // this method assumes that canMergeDown() previously returned true
    default void mergeDown(Layer layer) {
        int layerIndex = indexOf(layer);
        var bellowLayer = (ImageLayer) getLayer(layerIndex - 1);

        var bellowImage = bellowLayer.getImage();
        var maskViewModeBefore = getComp().getView().getMaskViewMode();
        var imageBefore = ImageUtils.copyImage(bellowImage);

        // apply the effect of the merged layer to the image of the image layer
        Graphics2D g = bellowImage.createGraphics();
        g.translate(-bellowLayer.getTx(), -bellowLayer.getTy());
        BufferedImage result = layer.applyLayer(g, bellowImage, false);
        if (result != null) {  // this was an adjustment
            bellowLayer.setImage(result);
        }
        g.dispose();

        bellowLayer.updateIconImage();

        deleteLayer(layer, false);

        History.add(new MergeDownEdit(this, layer,
            bellowLayer, imageBefore, maskViewModeBefore, layerIndex));
    }

    default boolean isHolderOfActiveLayer() {
        return getComp().getActiveLayerHolder() == this;
    }
}