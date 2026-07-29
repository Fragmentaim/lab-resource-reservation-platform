package com.fragment.labbooking.knowledge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fragment.labbooking.knowledge.entity.KbChunk;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface KbChunkMapper extends BaseMapper<KbChunk> {

    int insertBatch(@Param("chunks") List<KbChunk> chunks);
}
