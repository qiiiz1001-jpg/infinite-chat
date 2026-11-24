package cn.qingliao.userservice.config;

import cn.qingliao.common.constant.CommonConstant;

import io.minio.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 本地部署时准备头像桶；签名使用客户端可访问地址，初始化使用容器内部地址。 */
@Component
@ConditionalOnProperty(name = "minio.initialize", havingValue = "true")
public class MinioBucketInitializer implements ApplicationRunner {
    private final MinioClient client;

    public MinioBucketInitializer(
            @Value("${minio.internal-url:${minio.url}}") String endpoint,
            @Value("${minio.access-key}") String access,
            @Value("${minio.secret-key}") String secret) {
        client =
                MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(access, secret)
                        .region("us-east-1")
                        .build();
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        String bucket = CommonConstant.BUCKET_NAME;
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
            client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        // 仅公开头像路径的读取；写入仍须服务端签发短期 PUT URL。
        String policy =
                """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},
                "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/users/*"]}]}
                """
                        .formatted(bucket);
        client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket).config(policy).build());
    }
}
