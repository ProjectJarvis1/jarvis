package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import com.meta.wearable.dat.core.types.DatError
import com.meta.wearable.dat.core.types.DeviceSessionError

/**
 * Korean text for a glasses SDK error: what to do about it, then the SDK's own code in parentheses so a
 * screenshot is enough to look the cause up.
 */
fun glassesErrorMessage(error: DatError, prefix: String = "글라스 연결에 문제가 생겼어요."): String {
    val guide = when (error) {
        DeviceSessionError.NO_ELIGIBLE_DEVICE ->
            "글라스를 찾지 못했어요. 글라스를 펴서 쓰고, 다른 휴대폰에 연결돼 있으면 그쪽 블루투스를 꺼 주세요."
        DeviceSessionError.DEVICE_DISCONNECTED -> "글라스 연결이 끊겼어요. 글라스가 켜져 있고 가까이 있는지 확인해 주세요."
        DeviceSessionError.SESSION_ENDED_BY_DEVICE -> "글라스 쪽에서 연결을 끝냈어요. 다시 시작해 주세요."
        DeviceSessionError.CAPABILITY_DENIED -> "글라스가 카메라 사용을 허락하지 않았어요. Meta AI 앱의 앱 연결 화면에서 권한을 확인해 주세요."
        DeviceSessionError.BATTERY_CRITICAL -> "글라스 배터리가 거의 없어요. 충전한 뒤 다시 시도해 주세요."
        DeviceSessionError.THERMAL_CRITICAL, DeviceSessionError.THERMAL_EMERGENCY ->
            "글라스가 너무 뜨거워서 멈췄어요. 잠시 식힌 뒤 다시 시도해 주세요."
        DeviceSessionError.PEAK_POWER_SHUTDOWN -> "글라스가 전력 보호 때문에 멈췄어요. 충전 상태를 확인한 뒤 다시 시도해 주세요."
        DeviceSessionError.INSUFFICIENT_SDK_VERSION ->
            "자비스 앱이 너무 오래된 버전이라 이 글라스와 연결할 수 없어요. 자비스 앱을 최신 버전으로 업데이트해 주세요."
        DeviceSessionError.DWA_UNAVAILABLE -> "글라스의 연결 앱에 접근하지 못했어요. 글라스와 Meta AI 앱 연결을 확인한 뒤 다시 시도해 주세요."
        DeviceSessionError.DAT_APP_ON_THE_GLASSES_UPDATE_REQUIRED ->
            "글라스 소프트웨어 업데이트가 필요해요. Meta AI 앱의 앱 연결 화면에서 업데이트해 주세요."
        DeviceSessionError.DWA_OUT_OF_STU_RANGE -> "글라스와 앱 버전이 잘 맞지 않아 일부 기능이 불안정할 수 있어요."
        else -> prefix
    }
    val code = (error as? Enum<*>)?.name ?: error.description
    // The code goes first: the conversation screen shows this in a toast, which Android cuts to two lines.
    return "[$code] $guide"
}
