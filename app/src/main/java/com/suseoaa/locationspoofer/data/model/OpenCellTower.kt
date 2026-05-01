package com.suseoaa.locationspoofer.data.model

/**
 * OpenCelliD基站数据统一抽象模型
 * @param radio 网络类型(如LTE,GSM,UMTS,CDMA)
 * @param mcc Mobile Country Code(移动国家代码)
 * @param mnc Mobile Network Code(移动网络代码)
 * @param lac Location Area Code(位置区域码)/对于LTE即TAC
 * @param cid Cell Identity(小区标识)
 * @param signal 信号强度(由averageSignalStrength映射)
 */
data class OpenCellTower(
    val radio: String,
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int,
    val signal: Int
)
