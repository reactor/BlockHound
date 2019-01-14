docker run -i --rm \
    -v $PROJECT_ROOT_DIR:$PROJECT_ROOT_DIR \
    -w $PWD \
    -e CROSS_TRIPLE \
    multiarch/crossbuild@sha256:84a53371f554a3b3d321c9d1dfd485b8748ad6f378ab1ebed603fe1ff01f7b4d \
    "$@"