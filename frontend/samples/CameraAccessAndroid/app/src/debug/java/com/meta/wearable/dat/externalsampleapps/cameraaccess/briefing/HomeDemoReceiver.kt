package com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: fills the signed-in account's home with sample offers (made-up companies) so the
 * conversation home can be looked at without a calendar, mail or server. "clear" removes them again.
 *   adb shell am broadcast -n ai.jarvisglass.app/com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.HomeDemoReceiver
 *   adb shell am broadcast -n ai.jarvisglass.app/com.meta.wearable.dat.externalsampleapps.cameraaccess.briefing.HomeDemoReceiver --ez clear true
 */
class HomeDemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("clear", false)) {
            listOf("morning", "mail", "meeting", "deadline").forEach { ProactiveStore.dismiss(context, "demo-$it") }
            return
        }
        val now = System.currentTimeMillis()
        val min = 60_000L
        fun offer(id: String, kind: OfferKind, title: String, message: String, detail: String, created: Long, event: Long) =
            ProactiveStore.record(context, "demo-$id", ProactiveOffer("demo-$id", kind, title, message, detail, created, event))
        offer("morning", OfferKind.MORNING, "오늘 브리핑", "좋은 아침이에요. 오늘 미팅 2건, 이번 주 마감 1건, 챙길 일 3개가 있어요.",
            "오늘\n일정: 오후 2시 한빛정밀 김 팀장님 미팅\n마감: 금요일 대성테크 입찰 서류 제출", now - 300 * min, now + 600 * min)
        offer("mail", OfferKind.MAIL, "대성테크 기술협상 참여 요청", "방금 대성테크에서 기술협상에 참여해 달라는 메일이 왔어요. 지난 미팅 기록이랑 견적서 초안을 모아 드릴까요?",
            "메일\n보낸 사람: 대성테크 구매팀\n지난 미팅: 10월 28일 사양 협의", now - 190 * min, now - 190 * min)
        offer("meeting", OfferKind.MEETING, "한빛정밀 김 팀장님 미팅", "2시에 한빛정밀 김 팀장님 미팅이 있어요. 지난번에 금요일까지 견적서를 보내기로 하셨어요.",
            "오늘 오후 2시\n지난 대화: 견적서는 금요일까지 보내기로 함", now - 20 * min, now + 20 * min)
        offer("deadline", OfferKind.DEADLINE, "대성테크 입찰 서류 제출", "11월 20일(금)이 대성테크 입찰 서류 제출일이에요. 관련 기록 모아 드릴까요?",
            "11월 20일\n관련 기록: 사양서 초안, 지난 미팅 메모", now - 5 * min, now + 3 * 24 * 60 * min)
    }
}
