fn main() {
    prost_build::compile_protos(&["src/proto/portal.proto"], &["src/proto"]).unwrap();
}
