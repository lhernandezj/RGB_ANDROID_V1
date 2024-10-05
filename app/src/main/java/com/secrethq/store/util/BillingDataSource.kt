package com.secrethq.store.util

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchaseHistoryRecord
import com.android.billingclient.api.PurchaseHistoryResponseListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchaseHistoryParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.pow

import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableSharedFlow
import com.secrethq.utils.PTServicesBridge
import kotlinx.coroutines.coroutineScope

class BillingDataSource(
    private val applicationContext: Context,
    private val externalScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : DefaultLifecycleObserver, PurchasesUpdatedListener, BillingClientStateListener,
    ProductDetailsResponseListener, PurchasesResponseListener {

    /**
     * Cached in-app product purchases details.
     */
    private var cachedPurchasesList: List<Purchase>? = null

    /**
     * ProductDetails for all known products.
     */
    val productDetailsMap: MutableMap<String, MutableLiveData<ProductDetails?>> = mutableMapOf()

    // Observables that are used to communicate state.
    private val purchaseConsumptionInProcess: MutableSet<Purchase> = HashSet()
    private val billingFlowInProcess = MutableStateFlow(false)

    val BILLING_RESPONSE_RESULT_OK = 0
    val BILLING_RESPONSE_RESULT_USER_CANCELED = 1
    val BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE = 3
    val BILLING_RESPONSE_RESULT_ITEM_UNAVAILABLE = 4
    val BILLING_RESPONSE_RESULT_DEVELOPER_ERROR = 5
    val BILLING_RESPONSE_RESULT_ERROR = 6
    val BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED = 7
    val BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED = 8
    val BILLING_RESPONSE_RESULT_NO_RESTORE = 9
    val BILLING_RESPONSE_RESULT_RESTORE_COMPLETED = 10

    private  var activity: Activity? = null
    private  var activeSKU: String? = null
    private  var isConsumable:Boolean = false
    private lateinit var callback: (Int, String) -> Void?
    private lateinit var restoreCallback: (Int, String) -> Void?
    private lateinit var pendingCallback: (Int, String) -> Void?

    /**
     * Instantiate a new BillingClient instance.
     */
    private lateinit var billingClient: BillingClient

    private var LIST_OF_CONSUMABLE_PRODUCTS: List<String> = emptyList()
    private var LIST_OF_NONCONSUMABLE_PRODUCTS: List<String> = emptyList()
    private var LIST_OF_ONE_TIME_PRODUCTS: List<String> = emptyList()
    private var LIST_OF_SUBSCRIPTION_PRODUCTS: List<String> = emptyList()


    // Function to add or update a ProductDetails entry in the map
    fun loadProductIds() {
        LIST_OF_CONSUMABLE_PRODUCTS = PTServicesBridge.getInAppIds("consumable").filter { productId -> (productId != null && productId.length > 0) }
        LIST_OF_NONCONSUMABLE_PRODUCTS = PTServicesBridge.getInAppIds("nonconsumable").filter { productId -> (productId != null && productId.length > 0) }
        LIST_OF_ONE_TIME_PRODUCTS =
        listOf(LIST_OF_CONSUMABLE_PRODUCTS, LIST_OF_NONCONSUMABLE_PRODUCTS)
            .flatten().toSet().toList()
        LIST_OF_SUBSCRIPTION_PRODUCTS = PTServicesBridge.getInAppIds("subscription").filter { productId -> (productId != null && productId.length > 0) }
    }
    fun addOrUpdateProductDetails(productId: String, details: ProductDetails?) {
        Log.d(TAG,"addOrUpdateProductDetails: $productId = $details")
        if (!productDetailsMap.containsKey(productId)) {
            // If the product ID is not already in the map, create a new MutableLiveData entry
            productDetailsMap[productId] = MutableLiveData(details)
        } else {
            // If it exists, update the existing MutableLiveData
            productDetailsMap[productId]?.postValue(details)
        }
    }

    // Function to retrieve a ProductDetails entry from the map
    fun getProductDetails(productId: String): MutableLiveData<ProductDetails?>? {
        return productDetailsMap[productId]
    }

    fun initialize() {
        Log.d(TAG, "initialize")
        // Create a new BillingClient in onCreate().
        // Since the BillingClient can only be used once, we need to create a new instance
        // after ending the previous connection to the Google Play Store in onDestroy().
        billingClient = BillingClient.newBuilder(applicationContext)
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        if (!billingClient.isReady) {
            Log.d(TAG, "BillingClient: Start connection...")
            billingClient.startConnection(this)
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        Log.d(TAG, "ON_CREATE")
        initialize()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        Log.d(TAG, "ON_DESTROY")
        if (billingClient.isReady) {
            Log.d(TAG, "BillingClient can only be used once -- closing connection")
            // BillingClient can only be used once.
            // After calling endConnection(), we must create a new BillingClient.
            billingClient.endConnection()
        }
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        Log.d(TAG, "onBillingSetupFinished: $responseCode $debugMessage")
        if (responseCode == BillingClient.BillingResponseCode.OK) {
            // The billing client is ready.
            // You can query product details and purchases here.
            if(LIST_OF_SUBSCRIPTION_PRODUCTS.size > 0) {
                querySubscriptionProductDetails()
                querySubscriptionPurchases()
            }
            if(LIST_OF_ONE_TIME_PRODUCTS.size > 0) {
                queryOneTimeProductDetails()
                queryOneTimeProductPurchases()
            }
        }
    }

    override fun onBillingServiceDisconnected() {
        Log.d(TAG, "onBillingServiceDisconnected")
    }

    /**
     * In order to make purchases, you need the [ProductDetails] for the item or subscription.
     * This is an asynchronous call that will receive a result in [onProductDetailsResponse].
     *
     * querySubscriptionProductDetails uses method calls from GPBL 5.0.0. PBL5, released in May 2022,
     * is backwards compatible with previous versions.
     * To learn more about this you can read:
     * https://developer.android.com/google/play/billing/compatibility
     */
    private fun querySubscriptionProductDetails() {
        Log.d(TAG, "querySubscriptionProductDetails")
        val params = QueryProductDetailsParams.newBuilder()

        val productList: MutableList<QueryProductDetailsParams.Product> = arrayListOf()
        for (product in LIST_OF_SUBSCRIPTION_PRODUCTS) {
            productList.add(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(product)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
        }

        params.setProductList(productList).let { productDetailsParams ->
            Log.d(TAG, "about to queryProductDetailsAsync with $productList and bc $billingClient")
            billingClient.queryProductDetailsAsync(productDetailsParams.build(), this)
        }
    }

    /**
     * In order to make purchases, you need the [ProductDetails] for one-time product.
     * This is an asynchronous call that will receive a result in [onProductDetailsResponse].
     *
     * queryOneTimeProductDetails uses the [BillingClient.queryProductDetailsAsync] method calls
     * from GPBL 5.0.0. PBL5, released in May 2022, is backwards compatible with previous versions.
     * To learn more about this you can read:
     * https://developer.android.com/google/play/billing/compatibility
     */
    private fun queryOneTimeProductDetails() {
        Log.d(TAG, "queryOneTimeProductDetails")
        val params = QueryProductDetailsParams.newBuilder()

        val productList = LIST_OF_ONE_TIME_PRODUCTS.map { product ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(product)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }

        params.apply {
            setProductList(productList)
        }.let { productDetailsParams ->
            billingClient.queryProductDetailsAsync(productDetailsParams.build(), this)
        }
    }

    /**
     * Receives the result from [querySubscriptionProductDetails].
     *
     * Store the ProductDetails and post them in the [basicSubProductWithProductDetails] and
     * [premiumSubProductWithProductDetails]. This allows other parts of the app to use the
     *  [ProductDetails] to show product information and make purchases.
     *
     * onProductDetailsResponse() uses method calls from GPBL 5.0.0. PBL5, released in May 2022,
     * is backwards compatible with previous versions.
     * To learn more about this you can read:
     * https://developer.android.com/google/play/billing/compatibility
     */
    override fun onProductDetailsResponse(
        billingResult: BillingResult,
        productDetailsList: MutableList<ProductDetails>
    ) {
        Log.d(TAG, "onProductDetailsResponse: $productDetailsList")
        val response = BillingResponse(billingResult.responseCode)
        val debugMessage = billingResult.debugMessage
        when {
            response.isOk -> {
                processProductDetails(productDetailsList)
            }

            response.isTerribleFailure -> {
                // These response codes are not expected.
                Log.w(
                    TAG,
                    "onProductDetailsResponse - Unexpected error: ${response.code} $debugMessage"
                )
            }

            else -> {
                Log.e(TAG, "onProductDetailsResponse: ${response.code} $debugMessage")
            }

        }
    }

    /**
     * This method is used to process the product details list returned by the [BillingClient]and
     * post the details to the [basicSubProductWithProductDetails] and
     * [premiumSubProductWithProductDetails] live data.
     *
     * @param productDetailsList The list of product details.
     *
     */
    private fun processProductDetails(productDetailsList: MutableList<ProductDetails>) {
        Log.d(TAG, "processProductDetails: $productDetailsList")
        if (productDetailsList.isEmpty()) {
            val expectedSubscriptionProductDetailsCount = LIST_OF_SUBSCRIPTION_PRODUCTS.size
            val expectedOneTimeProductDetailsCount = LIST_OF_ONE_TIME_PRODUCTS.size
            Log.e(
                TAG, "processProductDetails: " +
                        "Expected ${expectedSubscriptionProductDetailsCount} subscriptions, " +
                        "maybe ${expectedOneTimeProductDetailsCount} one-time purchasables, " +
                        "Found null ProductDetails. " +
                        "Check to see if the products you requested are correctly published " +
                        "in the Google Play Console."
            )
            postProductDetails(emptyList())
        } else {
            postProductDetails(productDetailsList)
        }
    }

    /**
     * This method is used to post the product details to the [basicSubProductWithProductDetails]
     * and [premiumSubProductWithProductDetails] live data.
     *
     * @param productDetailsList The list of product details.
     *
     */
    private fun postProductDetails(productDetailsList: List<ProductDetails>) {
        productDetailsList.forEach { productDetails ->
            addOrUpdateProductDetails(productDetails.productId, productDetails)
        }
    }

    /**
     * Query Google Play Billing for existing subscription purchases.
     *
     * New purchases will be provided to the PurchasesUpdatedListener.
     * You still need to check the Google Play Billing API to know when purchase tokens are removed.
     */
    fun querySubscriptionPurchases() {
        if (!billingClient.isReady) {
            Log.e(TAG, "querySubscriptionPurchases: BillingClient is not ready")
            billingClient.startConnection(this)
        }
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build(), this
        )
    }

    /**
     * Query Google Play Billing for existing one-time product purchases.
     *
     * New purchases will be provided to the PurchasesUpdatedListener.
     * You still need to check the Google Play Billing API to know when purchase tokens are removed.
     */
    fun queryOneTimeProductPurchases() {
        if (!billingClient.isReady) {
            Log.e(TAG, "queryOneTimeProductPurchases: BillingClient is not ready")
            billingClient.startConnection(this)
        }
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(), this
        )
    }

    /**
     * Callback from the billing library when queryPurchasesAsync is called.
     */
    override fun onQueryPurchasesResponse(
        billingResult: BillingResult,
        purchasesList: MutableList<Purchase>
    ) {
        processPurchases(purchasesList)
    }

    /**
     * Called by the Billing Library when new purchases are detected.
     */
    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        Log.d(TAG, "onPurchasesUpdated: $responseCode $debugMessage")
        when (responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases == null) {
                    Log.d(TAG, "onPurchasesUpdated: null purchase list")
                    processPurchases(null)
                } else {
                    processPurchases(purchases)
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "onPurchasesUpdated: User cancelled the purchase")
                callback(BILLING_RESPONSE_RESULT_USER_CANCELED, "User has cancelled the purchase.")
            }

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.i(TAG, "onPurchasesUpdated: The user already owns this item")
                if (isConsumable) {
                    if (purchases != null) {
                        processPurchases(purchases)
                    }
                }
                else {
                    callback(BILLING_RESPONSE_RESULT_OK, "Already owned. Restore")
                }
            }

            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> {
                Log.e(
                    TAG, "onPurchasesUpdated: Developer error means that Google Play does " +
                            "not recognize the configuration. If you are just getting started, " +
                            "make sure you have configured the application correctly in the " +
                            "Google Play Console. The product ID must match and the APK you " +
                            "are using must be signed with release keys."
                )
                callback(BILLING_RESPONSE_RESULT_DEVELOPER_ERROR, "Developer error, check your configuration")
            }
        }

        externalScope.launch {
            billingFlowInProcess.emit(false)
        }
    }

    /**
     * Fulfill purchases
     */
    private fun processPurchases(purchasesList: List<Purchase>?) {
        Log.d(TAG, "processPurchases: ${purchasesList?.size} purchase(s)")
        purchasesList?.let { list ->
            if (isUnchangedPurchaseList(list)) {
                Log.d(TAG, "processPurchases: Purchase list has not changed")
                return
            }
            externalScope.launch {
                processPurchaseList(purchasesList)
            }
        }
    }

    /**
     * Check whether the purchases have changed before posting changes.
     */
    private fun isUnchangedPurchaseList(purchasesList: List<Purchase>): Boolean {
        val isUnchanged = purchasesList == cachedPurchasesList
        if (!isUnchanged) {
            cachedPurchasesList = purchasesList
        }
        return isUnchanged
    }

    /**
     * Launching the billing flow.
     *
     * Launching the UI to make a purchase requires a reference to the Activity.
     */
    fun launchBillingFlow(activity: Activity, sku: String, isConsumable: Boolean, callback: (Int,String) -> Void?) {
        Log.d(TAG, "top of launchBillingFlow()")
        if (!billingClient.isReady) {
            Log.e(TAG, "launchBillingFlow: BillingClient is not ready")
            callback(BILLING_RESPONSE_RESULT_ERROR, "BillingClient is not ready. Try again later, log in to Google Play Store, enable IAPs, or update device to support Billing API v3.")
            return
        }

        this.callback = callback
        this.activity = activity
        this.activeSKU = sku
        this.isConsumable = isConsumable

        val productDetails = getProductDetails(sku)
        if(productDetails == null) {
            Log.e(TAG, "launchBillingFlow: unknown product " + sku)
            callback(BILLING_RESPONSE_RESULT_ERROR, "Unknown product " + sku)
            return
        }        

        val billingFlowParams = productDetails.value?.let {
            BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(it)
                            .build()
                    )
                )
                .build()
        } ?: run {
            Log.e(TAG,"launchBillingFlow: Failed to create BillingFlowParams due to null ProductDetails on $sku")
            callback(BILLING_RESPONSE_RESULT_ERROR, "launchBillingFlow: Failed to create BillingFlowParams due to null ProductDetails on $sku")
            return
        }

        val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        Log.d(TAG, "launchBillingFlow: BillingResponse $responseCode $debugMessage")
        if (responseCode == BillingClient.BillingResponseCode.OK) {
            externalScope.launch {
                billingFlowInProcess.emit(true)
            }
        } else {
            Log.e(TAG, "Billing failed: + " + debugMessage)
            callback(BILLING_RESPONSE_RESULT_ERROR, "Failed to launch Checkout: " + debugMessage)
        }
        return
    }

    /**
     * Acknowledge a purchase.
     *
     * https://developer.android.com/google/play/billing/billing_library_releases_notes#2_0_acknowledge
     *
     * Apps should acknowledge the purchase after confirming that the purchase token
     * has been associated with a user. This app only acknowledges purchases after
     * successfully receiving the subscription data back from the server.
     *
     * Developers can choose to acknowledge purchases from a server using the
     * Google Play Developer API. The server has direct access to the user database,
     * so using the Google Play Developer API for acknowledgement might be more reliable.
     * If the purchase token is not acknowledged within 3 days,
     * then Google Play will automatically refund and revoke the purchase.
     * This behavior helps ensure that users are not charged for subscriptions unless the
     * user has successfully received access to the content.
     * This eliminates a category of issues where users complain to developers
     * that they paid for something that the app is not giving to them.
     */

    suspend fun acknowledgePurchase(purchaseToken: String): Boolean {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()

        for (trial in 1..MAX_RETRY_ATTEMPT) {
            val response = suspendCoroutine<BillingResponse> { continuation ->
                billingClient.acknowledgePurchase(params) { billingResult ->
                    continuation.resume(BillingResponse(billingResult.responseCode))
                }
            }

            when {
                response.isOk -> {
                    Log.i(TAG, "Acknowledge success - token: $purchaseToken")
                    return true
                }

                response.canFailGracefully -> {
                    // Ignore the error
                    Log.i(TAG, "Token $purchaseToken is already owned.")
                    return true
                }

                response.isRecoverableError -> {
                    // Retry to acknowledge because these errors may be recoverable.
                    val duration = 500L * 2.0.pow(trial).toLong()
                    delay(duration)
                    if (trial < MAX_RETRY_ATTEMPT) {
                        Log.w(
                            TAG,
                            "Retrying($trial) to acknowledge for token $purchaseToken - " +
                                    "code: ${response.code}, message: " +
                                    response.code
                        )
                    }
                }

                response.isNonrecoverableError || response.isTerribleFailure -> {
                    Log.e(
                        TAG,
                        "Failed to acknowledge for token $purchaseToken - " +
                                "code: ${response.code}, message: " +
                                response.code
                    )
                    break
                }
            }
        }
        Log.w(TAG, "Failed to acknowledge purchase after retries. Will process again in future.")
        return false
    }

    private suspend fun processRestoredPurchasesList(purchases: List<Purchase>) = coroutineScope {
        if(purchases.size == 0) {
            restoreCallback(BILLING_RESPONSE_RESULT_RESTORE_COMPLETED, "No products needed to be restored")
        }
        else {
            for (purchase in purchases) {
                for (productId in purchase.products) {
                    if (isValidIAP(productId)) {
                        if (!isConsumableIAP(productId)) {
                            // Launch a coroutine within this coroutine scope
                            launch {
                                processNonConsumablePurchase(purchase, false)
                                restoreCallback(BILLING_RESPONSE_RESULT_OK, productId)
                            }
                        }
                    }
                }
            }

            // This line will be executed only after all launched coroutines finish
            restoreCallback(
                BILLING_RESPONSE_RESULT_RESTORE_COMPLETED,
                "All products have been restored"
            )
        }
    }


    private fun isValidIAP( sku: String): Boolean {
        if (LIST_OF_SUBSCRIPTION_PRODUCTS.contains(sku)) {
            return true
        }
        if (LIST_OF_ONE_TIME_PRODUCTS.contains(sku)) {
            return true
        }
        return false
    }

    private fun isConsumableIAP(sku: String): Boolean {
        if(LIST_OF_CONSUMABLE_PRODUCTS.contains(sku)) {
            return true
        }
        return false
    }

    private fun processPurchaseList(purchases: List<Purchase>?) {
        if (null != purchases) {
            for (purchase in purchases) {
                val purchaseState = purchase.purchaseState
                if (purchaseState == Purchase.PurchaseState.PURCHASED) {
                    if (!purchase.isAcknowledged && !isConsumable) {
                        externalScope.launch {
                            processNonConsumablePurchase(purchase)
                        }
                    }
                    else if (!purchase.isAcknowledged && isConsumable) {
                        externalScope.launch {
                            processConsumablePurchase(purchase)
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "Empty purchase list.")
            callback(BILLING_RESPONSE_RESULT_ERROR,"Empty purchase list.")
        }
    }

    private suspend fun processNonConsumablePurchase(purchase: Purchase, wasPending: Boolean = false) {
        for (sku in purchase.products) {
            // Acknowledge item and change its state
            var success = this.acknowledgePurchase(purchase.purchaseToken)

            if (!success) {
                Log.e(
                    TAG,
                    "Error acknowledging purchase: ${purchase.products}"
                )
                if (wasPending) {
                    pendingCallback(BILLING_RESPONSE_RESULT_ERROR, "Error acknowledging purchase: ${purchase.products}")
                }
                else {
                    callback(BILLING_RESPONSE_RESULT_ERROR, "Error acknowledging purchase: ${purchase.products}")
                }
                return
            }

            if (wasPending) {
                pendingCallback(BILLING_RESPONSE_RESULT_OK, "Purchase successful.")
            }
            else {
                callback(BILLING_RESPONSE_RESULT_OK, "Purchase successful.")
            }
        }
    }

    /**
     * Internal call only. Assumes that all signature checks have been completed and the purchase
     * is ready to be consumed. If the sku is already being consumed, does nothing.
     * @param purchase purchase to consume
     */
    private suspend fun processConsumablePurchase(purchase: Purchase, wasPending: Boolean = false) {
        // weak check to make sure we're not already consuming the sku
        if (purchaseConsumptionInProcess.contains(purchase)) {
            if (wasPending) {
                pendingCallback(BILLING_RESPONSE_RESULT_ERROR, "Already consuming the product.")
                return
            }
            callback(BILLING_RESPONSE_RESULT_ERROR, "Already consuming the product.")
            return
        }
        purchaseConsumptionInProcess.add(purchase)

        billingClient.consumeAsync(
            ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        ) { billingResult, _ ->
            purchaseConsumptionInProcess.remove(purchase)
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "Consumption successful")
                externalScope.launch {
                    if (wasPending) {
                        pendingCallback(BILLING_RESPONSE_RESULT_OK, "Purchase successful.")
                    } else {
                        callback(BILLING_RESPONSE_RESULT_OK, "Purchase successful.")
                    }
                }
            } else {
                Log.e(TAG, "Error while consuming: ${billingResult.debugMessage}")
                if (wasPending) {
                    pendingCallback(BILLING_RESPONSE_RESULT_ERROR, "Purchase was not successful.")
                }
                else {
                    callback(BILLING_RESPONSE_RESULT_ERROR, "Purchase was not successful.")
                }
            }
        }
    }

    /*
     * Called after the IAP product IDs have been populated
     */
    fun loadProductDetails(activity: Activity?, callback: (Int, String) -> Void?) {
        this.activity = activity
        loadProductIds()
        if(LIST_OF_SUBSCRIPTION_PRODUCTS.size > 0) {
            querySubscriptionProductDetails()
            querySubscriptionPurchases()
        }
        if(LIST_OF_ONE_TIME_PRODUCTS.size > 0) {
            queryOneTimeProductDetails()
            queryOneTimeProductPurchases()
        }
        // TODO: improve error handling
        callback(BILLING_RESPONSE_RESULT_OK, "Product details loaded.")
    }

    fun restorePreviousIAPs(activity: Activity?, callback: (Int, String) -> Void?) {
        this.activity = activity
        this.restoreCallback = callback

        // Use queryPurchasesAsync to query the active purchases
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { billingResult, purchasesList ->
            // Handle the purchase history response
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                if (purchasesList.isNullOrEmpty()) {
                    Log.d(TAG, "Nothing to restore.")
                    restoreCallback(BILLING_RESPONSE_RESULT_NO_RESTORE, "No purchase history found. Skipping Restore")
                } else {
                    externalScope.launch {
                        processRestoredPurchasesList(purchasesList)
                    }
                }
            } else {
                restoreCallback(BILLING_RESPONSE_RESULT_ERROR, "Could not restore purchases at this time, try again later")
            }
        }
    }

    companion object {
        private const val TAG = "BillingDataSource"
        private const val MAX_RETRY_ATTEMPT = 3

        @Volatile
        private var INSTANCE: BillingDataSource? = null

        fun getInstance(applicationContext: Context): BillingDataSource =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BillingDataSource(applicationContext).also { INSTANCE = it }
            }
    }
}

@JvmInline
private value class BillingResponse(val code: Int) {
    val isOk: Boolean
        get() = code == BillingClient.BillingResponseCode.OK
    val canFailGracefully: Boolean
        get() = code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
    val isRecoverableError: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.ERROR,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        )
    val isNonrecoverableError: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
        )
    val isTerribleFailure: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
            BillingClient.BillingResponseCode.USER_CANCELED,
        )
}
