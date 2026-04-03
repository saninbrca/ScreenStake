package com.detox.app.presentation.screens.pointshop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.detox.app.data.remote.firebase.AnalyticsService
import com.detox.app.domain.repository.PointsRepository
import com.detox.app.domain.usecase.RedeemRewardUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ShopItem(
    val id: String,
    val nameRes: Int,
    val descriptionRes: Int,
    val pointsCost: Int,
    val iconEmoji: String
)

sealed interface PointShopUiState {
    data object Idle : PointShopUiState
    data object Loading : PointShopUiState
    data class RedeemSuccess(val itemName: String) : PointShopUiState
    data class Error(val message: String) : PointShopUiState
}

@HiltViewModel
class PointShopViewModel @Inject constructor(
    private val pointsRepository: PointsRepository,
    private val redeemRewardUseCase: RedeemRewardUseCase,
    private val analyticsService: AnalyticsService
) : ViewModel() {

    /** Live points balance — updates immediately after any transaction. */
    val pointsBalance: StateFlow<Int> = pointsRepository.getTotalPointsBalance()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _uiState = MutableStateFlow<PointShopUiState>(PointShopUiState.Idle)
    val uiState: StateFlow<PointShopUiState> = _uiState.asStateFlow()

    /** Shop catalogue — defined per CLAUDE.md business rules. */
    val shopItems: List<ShopItem> = listOf(
        ShopItem(
            id = "theme_dark_neon",
            nameRes = com.detox.app.R.string.shop_item_dark_neon_name,
            descriptionRes = com.detox.app.R.string.shop_item_dark_neon_desc,
            pointsCost = 50,
            iconEmoji = "🌌"
        ),
        ShopItem(
            id = "theme_retro",
            nameRes = com.detox.app.R.string.shop_item_retro_name,
            descriptionRes = com.detox.app.R.string.shop_item_retro_desc,
            pointsCost = 50,
            iconEmoji = "📺"
        ),
        ShopItem(
            id = "joker_card",
            nameRes = com.detox.app.R.string.shop_item_joker_name,
            descriptionRes = com.detox.app.R.string.shop_item_joker_desc,
            pointsCost = 30,
            iconEmoji = "🃏"
        ),
        ShopItem(
            id = "custom_icon",
            nameRes = com.detox.app.R.string.shop_item_icon_name,
            descriptionRes = com.detox.app.R.string.shop_item_icon_desc,
            pointsCost = 100,
            iconEmoji = "🎨"
        ),
        ShopItem(
            id = "premium_badge",
            nameRes = com.detox.app.R.string.shop_item_badge_name,
            descriptionRes = com.detox.app.R.string.shop_item_badge_desc,
            pointsCost = 200,
            iconEmoji = "💎"
        )
    )

    fun redeem(item: ShopItem) {
        viewModelScope.launch {
            _uiState.value = PointShopUiState.Loading
            redeemRewardUseCase(item.id, item.pointsCost)
                .onSuccess {
                    analyticsService.logRewardRedeemed(item.id, item.pointsCost)
                    _uiState.value = PointShopUiState.RedeemSuccess(item.id)
                }
                .onFailure { e ->
                    _uiState.value = PointShopUiState.Error(
                        e.message ?: "Redemption failed"
                    )
                }
        }
    }

    fun clearState() {
        _uiState.value = PointShopUiState.Idle
    }
}
