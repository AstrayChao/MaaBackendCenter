package plus.maa.backend.task;

import lombok.*;
import lombok.experimental.FieldDefaults;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import plus.maa.backend.repository.CopilotRatingRepository;
import plus.maa.backend.repository.CopilotRepository;
import plus.maa.backend.repository.RatingRepository;
import plus.maa.backend.repository.RedisCache;
import plus.maa.backend.repository.entity.Copilot;
import plus.maa.backend.repository.entity.CopilotRating;
import plus.maa.backend.repository.entity.Rating;
import plus.maa.backend.service.CopilotService;
import plus.maa.backend.service.model.RatingType;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 作业热度值刷入任务，每日执行，用于计算基于时间的热度值
 *
 * @author dove
 * created on 2023.05.03
 */
@Component
@RequiredArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
public class CopilotScoreRefreshTask {

    RedisCache redisCache;
    CopilotRepository copilotRepository;
    CopilotRatingRepository copilotRatingRepository;
    RatingRepository ratingRepository;
    MongoTemplate mongoTemplate;

    /**
     * 热度值刷入任务，每日三点执行
     */
    @Scheduled(cron = "0 12 23 * * ?")
    public void refreshHotScores() {
        List<Copilot> copilots = copilotRepository.findAllByDeleteIsFalse();
        List<Copilot> changedCopilots = new ArrayList<>();
        List<Long> copilotIds = copilots.stream().map(Copilot::getCopilotId).toList();
        // 旧版评级系统迁移
        List<CopilotRating> ratings = copilotRatingRepository.findByCopilotIdInAndDelete(copilotIds, false);
        if (ratings != null && !ratings.isEmpty()) {
            // 数据迁移
            Map<Long, CopilotRating> ratingById = ratings.stream()
                    .collect(Collectors.toMap(CopilotRating::getCopilotId, Function.identity(), (v1, v2) -> v1));
            for (Copilot copilot : copilots) {
                CopilotRating rating = ratingById.get(copilot.getCopilotId());
                if (rating != null) {
                    copilot.setRatingLevel(rating.getRatingLevel());
                    copilot.setRatingRatio(rating.getRatingRatio());
                    List<Rating> ratingList = new ArrayList<>();
                    if (rating.getRatingUsers() != null) {
                        for (var ratingUser : rating.getRatingUsers()) {
                            if ("Like".equals(ratingUser.getRating())) {
                                copilot.setLikeCount(copilot.getLikeCount() + 1);
                            } else if ("Dislike".equals(ratingUser.getRating())) {
                                copilot.setDislikeCount(copilot.getDislikeCount() + 1);
                            }
                            Rating newRating = new Rating()
                                    .setType(Rating.KeyType.COPILOT)
                                    .setKey(Long.toString(copilot.getCopilotId()))
                                    .setUserId(ratingUser.getUserId())
                                    .setRating(RatingType.fromRatingType(ratingUser.getRating()))
                                    .setRateTime(ratingUser.getRateTime());

                            ratingList.add(newRating);
                        }
                        ratingRepository.insert(ratingList);
                        rating.setDelete(true);
                        copilotRatingRepository.save(rating);
                    }
                }
            }
        }   // 迁移完成

        List<String> copilotIdSTRs = copilotIds.stream().map(String::valueOf).toList();
        // 批量获取最近七天的点赞和点踩数量
        LocalDateTime now = LocalDateTime.now();
        List<RatingCount> likeCounts = counts(copilotIdSTRs, RatingType.LIKE, now.minusDays(7));
        List<RatingCount> dislikeCounts = counts(copilotIdSTRs, RatingType.DISLIKE, now.minusDays(7));
        Map<String, Long> likeCountMap = likeCounts.stream().collect(Collectors.toMap(RatingCount::getKey, RatingCount::getCount));
        Map<String, Long> dislikeCountMap = dislikeCounts.stream().collect(Collectors.toMap(RatingCount::getKey, RatingCount::getCount));
        // 计算热度值
        for (Copilot copilot : copilots) {
            long likeCount = likeCountMap.getOrDefault(Long.toString(copilot.getCopilotId()), 1L);
            long dislikeCount = dislikeCountMap.getOrDefault(Long.toString(copilot.getCopilotId()), 0L);
            double hotScore = CopilotService.getHotScore(copilot, likeCount, dislikeCount);
            copilot.setHotScore(hotScore);
            changedCopilots.add(copilot);
        }

        copilotRepository.saveAll(changedCopilots);
        // 移除首页热度缓存
        redisCache.removeCacheByPattern("home:hot:*");
    }

    private List<RatingCount> counts(Collection<String> keys, RatingType rating, LocalDateTime startTime) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria
                        .where("type").is(Rating.KeyType.COPILOT)
                        .and("key").in(keys)
                        .and("rating").is(rating)
                        .and("rateTime").gte(startTime)
                ),
                Aggregation.group("key").count().as("count")
                        .first("key").as("key"),
                Aggregation.project("key", "count")
        ).withOptions(Aggregation.newAggregationOptions().allowDiskUse(true).build());  // 放弃内存优化，使用磁盘优化，免得内存炸了
        return mongoTemplate.aggregate(aggregation, Rating.class, RatingCount.class).getMappedResults();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RatingCount {
        private String key;
        private long count;
    }
}
