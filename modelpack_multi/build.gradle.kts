// On-demand asset pack of the `multi` embedding model (docs/specs/embeddings.md §5.2, ticket M4-06).
// Its files come from tools/model (`build_model.py --model multi --install`) and are never committed.
plugins {
    alias(libs.plugins.android.asset.pack)
    alias(libs.plugins.seekmind.model.pack)
}

assetPack {
    packName.set("modelpack_multi")
    dynamicDelivery {
        deliveryType.set("on-demand")
    }
}
