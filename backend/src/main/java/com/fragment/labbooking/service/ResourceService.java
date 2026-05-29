package com.fragment.labbooking.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.fragment.labbooking.dto.ResourceAddDTO;
import com.fragment.labbooking.dto.ResourcePageQueryDTO;
import com.fragment.labbooking.dto.ResourceQueryDTO;
import com.fragment.labbooking.dto.ResourceUpdateDTO;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.vo.ResourceVO;

import java.util.List;

/**
* @author fragment
* @description 閽堝琛ㄣ€恟esource(璧勬簮琛?銆戠殑鏁版嵁搴撴搷浣淪ervice
* @createDate 2026-03-28 15:02:51
*/
public interface ResourceService extends IService<Resource> {
    List<ResourceVO> search(ResourceQueryDTO queryDTO);
    Page<ResourceVO> pageResource(ResourcePageQueryDTO queryDTO);
    ResourceVO getResourceById(Long id);
    void addResource(ResourceAddDTO dto);
    void updateResource(ResourceUpdateDTO dto);
    void removeResource(Long id);

}
