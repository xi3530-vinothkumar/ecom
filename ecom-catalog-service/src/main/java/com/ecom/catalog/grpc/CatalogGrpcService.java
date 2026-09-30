package com.ecom.catalog.grpc;

import com.ecom.catalog.application.CategoryService;
import com.ecom.catalog.application.ProductQueryService;
import com.ecom.catalog.proto.v1.CatalogServiceGrpc;
import com.ecom.catalog.proto.v1.GetProductRequest;
import com.ecom.catalog.proto.v1.GetProductsBatchRequest;
import com.ecom.catalog.proto.v1.GetProductsBatchResponse;
import com.ecom.catalog.proto.v1.ListCategoriesRequest;
import com.ecom.catalog.proto.v1.ListCategoriesResponse;
import com.ecom.catalog.proto.v1.ProductItem;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

@Component
public class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {

    private final ProductQueryService queries;
    private final CategoryService categories;

    public CatalogGrpcService(ProductQueryService queries, CategoryService categories) {
        this.queries = queries;
        this.categories = categories;
    }

    @Override
    public void getProduct(GetProductRequest request, StreamObserver<ProductItem> out) {
        out.onNext(CatalogProtoMapper.item(queries.getBySku(request.getSku())));
        out.onCompleted();
    }

    @Override
    public void getProductsBatch(GetProductsBatchRequest request, StreamObserver<GetProductsBatchResponse> out) {
        var response = GetProductsBatchResponse.newBuilder();
        queries.getBySkus(request.getSkusList()).forEach(v -> response.addItems(CatalogProtoMapper.item(v)));
        out.onNext(response.build());
        out.onCompleted();
    }

    @Override
    public void listCategories(ListCategoriesRequest request, StreamObserver<ListCategoriesResponse> out) {
        var response = ListCategoriesResponse.newBuilder();
        categories.list().forEach(c -> response.addCategories(CatalogProtoMapper.category(c)));
        out.onNext(response.build());
        out.onCompleted();
    }
}
