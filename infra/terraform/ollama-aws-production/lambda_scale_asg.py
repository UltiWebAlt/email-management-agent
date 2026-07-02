import os
import boto3

asg = boto3.client("autoscaling")

def handler(event, context):
    asg_name = os.environ["ASG_NAME"]
    desired_capacity = int(event.get("desired_capacity", 0))

    asg.set_desired_capacity(
        AutoScalingGroupName=asg_name,
        DesiredCapacity=desired_capacity,
        HonorCooldown=False,
    )

    return {
        "asg_name": asg_name,
        "desired_capacity": desired_capacity,
    }
