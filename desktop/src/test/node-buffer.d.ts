// 앱 tsconfig는 node 타입을 싣지 않는다(브라우저 코드에 Node API가 섞이지 않게). 테스트 setup이 쓰는
// node:buffer의 File만 브라우저 File 타입으로 선언한다.
declare module "node:buffer" {
  export const File: typeof globalThis.File;
}
