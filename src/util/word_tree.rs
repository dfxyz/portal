use std::collections::HashMap;

#[derive(Default)]
pub(crate) struct WordTreeNode {
    is_leaf: bool,
    map: HashMap<String, Box<Self>>,
}

impl WordTreeNode {
    pub(crate) fn insert<S: Into<String>>(&mut self, words: Vec<S>) {
        if words.is_empty() {
            return;
        }
        let mut node_ptr = self as *mut Self;
        for word in words {
            let word = word.into();
            let node = unsafe { node_ptr.as_mut().unwrap() };
            match node.map.get_mut(&word) {
                None => {
                    let mut new_node = Box::new(Self::default());
                    node_ptr = new_node.as_mut() as _;
                    node.map.insert(word, new_node);
                }
                Some(n) => {
                    if n.is_leaf {
                        return;
                    }
                    node_ptr = n.as_mut() as _;
                }
            }
        }
        unsafe { node_ptr.as_mut().unwrap().is_leaf = true };
    }

    pub(crate) fn contains<S: AsRef<str>>(&self, words: &[S]) -> bool {
        if words.is_empty() {
            return false;
        }
        let mut node = self;
        for word in words {
            let word = word.as_ref();
            match node.map.get(word) {
                None => return false,
                Some(n) => {
                    if n.is_leaf {
                        return true;
                    }
                    node = n;
                }
            }
        }
        false
    }
}

#[test]
fn test() {
    let mut node = WordTreeNode::default();
    node.insert(vec!["com", "example", "api"]);
    assert!(!node.contains(&["com"]));
    assert!(!node.contains(&["com", "example"]));
    assert!(node.contains(&["com", "example", "api"]));
    assert!(node.contains(&["com", "example", "api", "prefix"]));
}
