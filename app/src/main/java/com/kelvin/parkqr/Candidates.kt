package com.kelvin.parkqr

import android.content.Context

/** 按"离当前定位的距离"排序的候选停车场，主界面、切换列表、悬浮窗共用同一套排序。 */
object Candidates {

    /** 全量排序：有坐标的按距离升序，没坐标的排最后。Double 为米，null=无坐标。 */
    fun ranked(ctx: Context, store: LotStore): List<Pair<Lot, Double?>> {
        val loc = Geo.lastKnown(ctx)
        return store.all()
            .map { lot ->
                val d = if (loc != null && lot.hasLocation) {
                    Geo.distance(loc.latitude, loc.longitude, lot.lat!!, lot.lng!!)
                } else null
                lot to d
            }
            .sortedWith(compareBy({ it.second == null }, { it.second ?: 0.0 }))
    }

    /** 悬浮窗/候选条用：最近的 n 个（只要有码的）。 */
    fun top(ctx: Context, store: LotStore, n: Int = 3): List<Pair<Lot, Double?>> =
        ranked(ctx, store).filter { it.first.hasCode }.take(n)
}
