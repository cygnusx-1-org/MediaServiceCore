package com.liskovsoft.googleapi.service

import com.liskovsoft.googleapi.cloudapi.CloudKeyClient
import com.liskovsoft.googleapi.cloudapi.CloudKeyFlow
import com.liskovsoft.googleapi.cloudapi.CloudKeyStep
import com.liskovsoft.sharedutils.rx.RxHelper
import io.reactivex.Observable

/**
 * The user's own YouTube Data API key, from their Google Cloud account
 */
object CloudKeyService {
    /**
     * Finds the user's key, or makes one. Fails with a CloudKeyException.
     * @param accessToken a token with the cloud-platform scope
     * @return every step as it starts and ends. The last one, the test when it's done, has the key.
     */
    @JvmStatic
    fun getKeyObserve(accessToken: String): Observable<CloudKeyStep> {
        return RxHelper.createLong<CloudKeyStep> { emitter ->
            CloudKeyFlow.run(CloudKeyClient(accessToken), { emitter.onNext(it) })
            emitter.onComplete()
        }
    }
}
