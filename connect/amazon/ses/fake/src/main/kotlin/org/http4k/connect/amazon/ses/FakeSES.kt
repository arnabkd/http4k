package org.http4k.connect.amazon.ses

import org.http4k.aws.AwsCredentials
import org.http4k.chaos.ChaoticHttpHandler
import org.http4k.chaos.start
import org.http4k.connect.amazon.AwsRestJsonFake
import org.http4k.connect.amazon.core.model.AwsAccount
import org.http4k.connect.amazon.core.model.Region
import org.http4k.connect.storage.InMemory
import org.http4k.connect.storage.Storage
import org.http4k.core.Method.POST
import org.http4k.core.then
import org.http4k.filter.ServerFilters.CatchLensFailure
import org.http4k.routing.bind
import org.http4k.routing.routes

class FakeSES(
    val messagesBySender: Storage<List<EmailMessage>> = Storage.InMemory(),
    awsAccount: AwsAccount = AwsAccount.of("1234567890"),
    region: Region = Region.of("ldn-north-1")
) : ChaoticHttpHandler() {

    private val api = AwsRestJsonFake(SESMoshi, SES.awsService, region, awsAccount)

    override val app = CatchLensFailure()
        .then(
            routes(
                "/v2/email/outbound-emails" bind POST to api.SendEmail(messagesBySender),
            )
        )

    fun client() = SES.Http(Region.of("ldn-north-1"), { AwsCredentials("accessKey", "secret") }, this)
}

fun main() {
    FakeSES().start()
}
