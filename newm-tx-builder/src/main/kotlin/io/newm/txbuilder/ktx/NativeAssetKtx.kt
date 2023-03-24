package io.newm.txbuilder.ktx

import com.google.iot.cbor.CborByteString
import com.google.iot.cbor.CborInteger
import com.google.iot.cbor.CborMap
import io.newm.chain.grpc.NativeAsset
import io.newm.chain.util.hexToByteArray
import java.util.*

/**
 * Convert a list of NativeAssets into a map where like-assets are combined and the ordering is canonical cbor.
 */
fun List<NativeAsset>.toNativeAssetMap(): MutableMap<String, List<NativeAsset>> {
    val nativeAssetMap = toSortedNativeAssetMap()

    return nativeAssetMap.entries.associate { (key, value) ->
        key to value.sortedWithCanonicalCbor()
    }.toMutableMap()
}

/**
 * Convert to a map of NativeAssets that can be included directly in an OutputUtxo with canonical cbor ordering.
 */
fun List<NativeAsset>.toNativeAssetCborMap(): CborMap? {
    if (isEmpty()) {
        return null
    }
    val nativeAssetMap = toSortedNativeAssetMap()

    return CborMap.create(
        nativeAssetMap.entries.associate { (key, value) ->
            CborByteString.create(key.hexToByteArray()) to CborMap.create(
                value.sortedWithCanonicalCbor().associate { nativeAsset ->
                    CborByteString.create(nativeAsset.name.hexToByteArray()) to CborInteger.create(nativeAsset.amount.toBigInteger())
                }
            )
        }
    )
}

/**
 * Convert into a sorted map where amounts of the same asset type are summed.
 */
private fun List<NativeAsset>.toSortedNativeAssetMap(): SortedMap<String, List<NativeAsset>> {
    val nativeAssetMap = sortedMapOf<String, List<NativeAsset>>()
    this.forEach { nativeAsset ->
        val updatedNativeAssets: List<NativeAsset> = nativeAssetMap[nativeAsset.policy]?.let { nativeAssets ->
            nativeAssets.find { it.name == nativeAsset.name }?.let { na ->
                val prevAmount = na.amount
                val mutableList = nativeAssets.toMutableList()
                mutableList.remove(na)
                mutableList.add(
                    na.toBuilder().setAmount(na.amount + prevAmount).build()
                )
                mutableList
            } ?: (nativeAssets + nativeAsset)
        } ?: listOf(nativeAsset)
        nativeAssetMap[nativeAsset.policy] = updatedNativeAssets
    }
    return nativeAssetMap
}

/**
 * Canonical Cbor sort. Shorter name lengths first, then lexicographically
 */
private fun List<NativeAsset>.sortedWithCanonicalCbor(): List<NativeAsset> = this.sortedWith { nativeAsset, other ->
    var comparison = nativeAsset.name.length.compareTo(other.name.length)
    if (comparison == 0) {
        comparison = nativeAsset.name.compareTo(other.name)
    }
    comparison
}