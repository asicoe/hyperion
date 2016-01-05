package com.krux.hyperion.examples

import scala.language.postfixOps
import com.krux.hyperion.action.SnsAlarm
import com.krux.hyperion.activity.{SparkJobActivity, SparkActivity, SparkStep}
import com.krux.hyperion.datanode.S3DataNode
import com.krux.hyperion.expression.DateTimeFunctions.format
import com.krux.hyperion.expression.RuntimeNode
import com.krux.hyperion.Implicits._
import com.krux.hyperion.parameter._
import com.krux.hyperion.resource.SparkCluster
import com.krux.hyperion.WorkflowExpression
import com.krux.hyperion.{Schedule, DataPipelineDef, HyperionContext}
import com.typesafe.config.ConfigFactory

object ExampleSpark extends DataPipelineDef {

  val target = "the-target"
  val jar = s3 / "sample-jars" / "sample-jar-assembly-current.jar"

  override implicit val hc: HyperionContext = new HyperionContext(ConfigFactory.load("example"))

  override lazy val tags = Map("example" -> None, "ownerGroup" -> Some("spark"))

  override lazy val schedule = Schedule.cron
    .startAtActivation
    .every(1.day)
    .stopAfter(3)

  val location = S3KeyParameter("S3Location", s3"your-location")
  val instanceType = StringParameter("InstanceType", "c3.8xlarge")
  val instanceCount = IntegerParameter("InstanceCount", 8)
  val instanceBid = DoubleParameter("InstanceBid", 3.40)

  override def parameters: Iterable[Parameter[_]] = Seq(location, instanceType, instanceCount, instanceBid)

  val dataNode = S3DataNode(s3 / "some-bucket" / "some-path" /)

  // Actions
  val mailAction = SnsAlarm()
    .withSubject(s"Something happened at ${RuntimeNode.scheduledStartTime}")
    .withMessage(s"Some message $instanceCount x $instanceType @ $instanceBid for $location")
    .withTopicArn("arn:aws:sns:us-east-1:28619EXAMPLE:ExampleTopic")
    .withRole("DataPipelineDefaultResourceRole")

  // Resources
  val sparkCluster = SparkCluster()
    .withTaskInstanceCount(1)
    .withTaskInstanceType(instanceType)

  // First activity
  val filterActivity = SparkJobActivity(jar.toString, "com.krux.hyperion.FilterJob")(sparkCluster)
    .named("filterActivity")
    .onFail(mailAction)
    .withInput(dataNode)
    .withArguments(
      target,
      format(SparkActivity.scheduledStartTime - 3.days, "yyyy-MM-dd")
    )

  // Second activity
  val scoreStep1 = SparkStep(jar)
    .withMainClass("com.krux.hyperion.ScoreJob1")
    .withArguments(
      target,
      format(SparkActivity.scheduledStartTime - 3.days, "yyyy-MM-dd"),
      "denormalized"
    )

  val scoreStep2 = SparkStep(jar)
    .withMainClass("com.krux.hyperion.ScoreJob2")
    .withArguments(target, format(SparkActivity.scheduledStartTime - 3.days, "yyyy-MM-dd"))

  val scoreActivity = SparkActivity(sparkCluster)
    .named("scoreActivity")
    .withSteps(scoreStep1, scoreStep2)
    .onSuccess(mailAction)

  override def workflow = filterActivity ~> scoreActivity

}