import eval_set


def test_two_lost_queries_pass():
    assert eval_set.excess_loss(69, 67) is None


def test_a_third_lost_query_fails():
    assert eval_set.excess_loss(69, 66) == "quantization loses 3 queries (> 2)"


def test_a_gain_passes():
    assert eval_set.excess_loss(67, 69) is None
